/*
 * Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.nio.netty.internal.http2;

import static java.time.temporal.ChronoUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2PingFrame;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class Http2ChannelHealthCheckerTest {
    private static final Duration FAST_CHECKER_DURATION = Duration.ofMillis(100);

    private EmbeddedChannel channel;
    private Http2ChannelHealthChecker fastChecker;
    private Http2ChannelHealthChecker slowChecker;

    @Before
    public void setup() throws Exception {
        this.channel = new EmbeddedChannel();
        this.fastChecker = new Http2ChannelHealthChecker(channel, FAST_CHECKER_DURATION);
        this.slowChecker = new Http2ChannelHealthChecker(channel, Duration.ofSeconds(30));
    }

    @After
    public void teardown() {
        fastChecker.stop();
        slowChecker.stop();
        channel.close();
    }

    @Test
    public void isRunning_falseWhenCreated() {
        assertThat(slowChecker.isRunning()).isFalse();
    }

    @Test
    public void start_startsRunning() {
        slowChecker.start();
        assertThat(slowChecker.isRunning()).isTrue();
    }

    @Test
    public void start_sendsPingImmediately() {
        slowChecker.start();
        channel.runScheduledPendingTasks();

        DefaultHttp2PingFrame sentFrame = channel.readOutbound();

        assertThat(sentFrame).isNotNull();
        assertThat(sentFrame.ack()).isFalse();
    }

    @Test
    public void stop_stopsRunning() {
        slowChecker.start();
        assertThat(slowChecker.isRunning()).isTrue();
        slowChecker.stop();
        assertThat(slowChecker.isRunning()).isFalse();
    }

    @Test
    public void ignoredPingsResultInOneChannelException() throws InterruptedException {
        PipelineExceptionCatcher catcher = PipelineExceptionCatcher.register(channel);
        fastChecker.start();
        Thread.sleep(FAST_CHECKER_DURATION.toMillis());
        channel.runScheduledPendingTasks();

        assertThat(catcher.caughtExceptions).hasSize(1);
        assertThat(catcher.caughtExceptions.get(0)).isInstanceOf(IOException.class);
    }

    @Test
    public void respondedToPingsResultInNoAction() {
        PipelineExceptionCatcher catcher = PipelineExceptionCatcher.register(channel);

        channel.eventLoop().scheduleAtFixedRate(() -> channel.writeInbound(new DefaultHttp2PingFrame(0, true)),
                                                0, FAST_CHECKER_DURATION.toMillis(), TimeUnit.MILLISECONDS);
        fastChecker.start();

        Instant runEnd = Instant.now().plus(1, SECONDS);
        while (Instant.now().isBefore(runEnd) && fastChecker.isRunning()) {
            channel.runScheduledPendingTasks();
        }

        assertThat(catcher.caughtExceptions).isEmpty();
    }

    @Test
    public void nonAckPingsResultInOneChannelException() {
        PipelineExceptionCatcher catcher = PipelineExceptionCatcher.register(channel);

        channel.eventLoop().scheduleAtFixedRate(() -> channel.writeInbound(new DefaultHttp2PingFrame(0, false)),
                                                0, FAST_CHECKER_DURATION.toMillis(), TimeUnit.MILLISECONDS);
        fastChecker.start();

        Instant runEnd = Instant.now().plus(1, SECONDS);
        while (Instant.now().isBefore(runEnd) && fastChecker.isRunning()) {
            channel.runScheduledPendingTasks();
        }

        assertThat(catcher.caughtExceptions).hasSize(1);
        assertThat(catcher.caughtExceptions.get(0)).isInstanceOf(IOException.class);
    }

    @Test
    public void failedWriteResultsInOneChannelException() throws InterruptedException {
        PipelineExceptionCatcher catcher = PipelineExceptionCatcher.register(channel);
        FailingWriter.register(channel);
        fastChecker.start();
        channel.runPendingTasks();
        assertThat(catcher.caughtExceptions).hasSize(1);
        assertThat(catcher.caughtExceptions.get(0)).isInstanceOf(IOException.class);
    }

    @Test
    public void ackPingsAreNotForwardedToOtherHandlers() throws InterruptedException {
        fastChecker.start();
        PingReadCatcher catcher = PingReadCatcher.register(channel);
        channel.writeInbound(new DefaultHttp2PingFrame(0, true));

        channel.runPendingTasks();

        assertThat(catcher.caughtPings).isEmpty();
    }

    @Test
    public void nonAckPingsAreForwardedToOtherHandlers() throws InterruptedException {
        fastChecker.start();
        PingReadCatcher catcher = PingReadCatcher.register(channel);
        channel.writeInbound(new DefaultHttp2PingFrame(0, false));

        channel.runPendingTasks();

        assertThat(catcher.caughtPings).hasSize(1);
    }

    private static final class PingReadCatcher extends SimpleChannelInboundHandler<Http2PingFrame> {
        private final List<Http2PingFrame> caughtPings = Collections.synchronizedList(new ArrayList<>());

        public static PingReadCatcher register(Channel channel) {
            PingReadCatcher catcher = new PingReadCatcher();
            channel.pipeline().addLast(catcher);
            return catcher;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2PingFrame msg) {
            caughtPings.add(msg);
        }
    }

    private static final class PipelineExceptionCatcher extends ChannelInboundHandlerAdapter {
        private final List<Throwable> caughtExceptions = Collections.synchronizedList(new ArrayList<>());

        public static PipelineExceptionCatcher register(Channel channel) {
            PipelineExceptionCatcher catcher = new PipelineExceptionCatcher();
            channel.pipeline().addLast(catcher);
            return catcher;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            caughtExceptions.add(cause);
            super.exceptionCaught(ctx, cause);
        }
    }

    private static final class FailingWriter extends ChannelOutboundHandlerAdapter {
        public static FailingWriter register(Channel channel) {
            FailingWriter catcher = new FailingWriter();
            channel.pipeline().addLast(catcher);
            return catcher;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            promise.setFailure(new IOException("Failed!"));
        }
    }
}