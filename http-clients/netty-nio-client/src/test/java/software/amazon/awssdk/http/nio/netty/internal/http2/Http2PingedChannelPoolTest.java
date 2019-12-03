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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.pool.ChannelPool;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.FailedFuture;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.SucceededFuture;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.nio.netty.internal.ChannelAttributeKey;
import software.amazon.awssdk.utils.CompletableFutureUtils;

public class Http2PingedChannelPoolTest {
    private final DefaultEventLoop eventLoop = new DefaultEventLoop();

    private final Promise<Void> voidPromise = mock(Promise.class);

    private final Promise<Channel> channelPromise = mock(Promise.class);

    private final Channel channel = mock(Channel.class);

    private final ChannelPool delegatePool = mock(ChannelPool.class);

    private final Http2ChannelHealthChecker healthChecker = mock(Http2ChannelHealthChecker.class);

    private final Function<Channel, Http2ChannelHealthChecker> healthCheckerFactory = mock(Function.class);

    private final Http2PingedChannelPool channelPool = new Http2PingedChannelPool(delegatePool, healthCheckerFactory);

    @Before
    public void setup() {
        Mockito.reset(voidPromise, channelPromise, channel, delegatePool, healthChecker, healthCheckerFactory);
        Mockito.when(channel.attr(isA(AttributeKey.class))).thenReturn(mock(Attribute.class));
        Mockito.when(healthCheckerFactory.apply(isA(Channel.class))).thenReturn(healthChecker);
    }

    @Test
    public void close_delegatesClose() {
        channelPool.close();
        Mockito.verify(delegatePool).close();
    }

    @Test
    public void release_delegatesRelease() {
        channelPool.release(channel);
        Mockito.verify(delegatePool).release(channel);
    }

    @Test
    public void releaseWithPromise_delegatesRelease() {
        channelPool.release(channel, voidPromise);
        Mockito.verify(delegatePool).release(channel, voidPromise);
    }

    @Test
    public void acquire_doesntPingIfAcquireFails() {
        Mockito.when(delegatePool.acquire()).thenReturn(new FailedFuture<>(eventLoop, new IOException()));

        Future<Channel> result = channelPool.acquire();
        waitForPendingTasks();

        assertThat(result.isSuccess()).isFalse();
        verifyZeroInteractions(healthCheckerFactory);
    }

    @Test
    public void acquire_doesntPingIfProtocolFutureFails() {
        Mockito.when(delegatePool.acquire()).thenReturn(new SucceededFuture<>(eventLoop, channel));
        mockProtocolFuture(CompletableFutureUtils.failedFuture(new IOException()));

        Future<Channel> result = channelPool.acquire();
        waitForPendingTasks();

        assertThat(result.isSuccess()).isTrue();
        verifyZeroInteractions(healthCheckerFactory);
    }

    @Test
    public void acquire_doesntPingIfProtocolIsHttp1() {
        Mockito.when(delegatePool.acquire()).thenReturn(new SucceededFuture<>(eventLoop, channel));
        mockProtocolFuture(CompletableFuture.completedFuture(Protocol.HTTP1_1));

        Future<Channel> result = channelPool.acquire();
        waitForPendingTasks();

        assertThat(result.isSuccess()).isTrue();
        verifyZeroInteractions(healthCheckerFactory);
    }

    @Test
    public void acquire_pingsIfProtocolIsHttp2() {
        Mockito.when(delegatePool.acquire()).thenReturn(new SucceededFuture<>(eventLoop, channel));
        mockProtocolFuture(CompletableFuture.completedFuture(Protocol.HTTP2));

        Future<Channel> result = channelPool.acquire();
        waitForPendingTasks();

        assertThat(result.isSuccess()).isTrue();
        verify(healthChecker).start();
        verifyNoMoreInteractions(healthChecker);
    }

    @Test
    public void acquireWithPromise_doesntPingIfAcquireFails() {
        Mockito.when(delegatePool.acquire(channelPromise)).thenReturn(new FailedFuture<>(eventLoop, new IOException()));

        Future<Channel> result = channelPool.acquire(channelPromise);
        waitForPendingTasks();

        assertThat(result.isSuccess()).isFalse();
        verifyZeroInteractions(healthCheckerFactory);
    }

    @Test
    public void acquireWithPromise_doesntPingIfProtocolFutureFails() {
        Mockito.when(delegatePool.acquire(channelPromise)).thenReturn(new SucceededFuture<>(eventLoop, channel));
        mockProtocolFuture(CompletableFutureUtils.failedFuture(new IOException()));

        Future<Channel> result = channelPool.acquire(channelPromise);
        waitForPendingTasks();

        assertThat(result.isSuccess()).isTrue();
        verifyZeroInteractions(healthCheckerFactory);
    }

    @Test
    public void acquireWithPromise_doesntPingIfProtocolIsHttp1() {
        Mockito.when(delegatePool.acquire(channelPromise)).thenReturn(new SucceededFuture<>(eventLoop, channel));
        mockProtocolFuture(CompletableFuture.completedFuture(Protocol.HTTP1_1));

        Future<Channel> result = channelPool.acquire(channelPromise);
        waitForPendingTasks();

        assertThat(result.isSuccess()).isTrue();
        verifyZeroInteractions(healthCheckerFactory);
    }

    @Test
    public void acquireWithPromise_pingsIfProtocolIsHttp2() {
        Mockito.when(delegatePool.acquire(channelPromise)).thenReturn(new SucceededFuture<>(eventLoop, channel));
        mockProtocolFuture(CompletableFuture.completedFuture(Protocol.HTTP2));

        Future<Channel> result = channelPool.acquire(channelPromise);
        waitForPendingTasks();

        assertThat(result.isSuccess()).isTrue();
        verify(healthChecker).start();
        verifyNoMoreInteractions(healthChecker);
    }

    private void mockProtocolFuture(CompletableFuture<Protocol> protocolFuture) {
        Attribute<CompletableFuture<Protocol>> mockAttribute = mock(Attribute.class);
        Mockito.when(mockAttribute.get()).thenReturn(protocolFuture);
        Mockito.when(channel.attr(ChannelAttributeKey.PROTOCOL_FUTURE)).thenReturn(mockAttribute);
    }

    private void waitForPendingTasks() {
        CountDownLatch latch = new CountDownLatch(1);
        eventLoop.execute(latch::countDown);
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new Error(e);
        }
    }
}