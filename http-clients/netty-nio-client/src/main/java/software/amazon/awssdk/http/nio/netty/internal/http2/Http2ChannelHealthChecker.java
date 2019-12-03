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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static software.amazon.awssdk.http.nio.netty.internal.utils.NettyUtils.warnIfNotInEventLoop;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.utils.Logger;

/**
 * Used by {@link Http2PingedChannelPool} to periodically check the health of HTTP2 connections via PING frames.
 *
 * If a channel is found to be unhealthy, this will invoke {@link ChannelPipeline#fireExceptionCaught(Throwable)}.
 */
@SdkInternalApi
class Http2ChannelHealthChecker {
    private static final Logger log = Logger.loggerFor(Http2ChannelHealthChecker.class);
    private static final Http2PingFrame DEFAULT_PING_FRAME = new DefaultHttp2PingFrame(0);

    private final Channel channel;
    private final long pingTimeoutMillis;

    private ScheduledFuture<?> periodicPing;
    private long lastPingSendTime = 0;
    private long lastPingAckTime = Long.MAX_VALUE;

    Http2ChannelHealthChecker(Channel channel, Duration pingTimeout) {
        this.channel = channel;
        this.pingTimeoutMillis = pingTimeout.toMillis();
    }

    public void start() {
        warnIfNotInEventLoop(channel.eventLoop());

        channel.pipeline().addLast(new PingResponseListener());
        periodicPing = channel.eventLoop().scheduleAtFixedRate(this::doPeriodicPing, 0, pingTimeoutMillis, MILLISECONDS);
        channel.closeFuture().addListener(i -> stop());
    }

    public boolean isRunning() {
        warnIfNotInEventLoop(channel.eventLoop());

        return periodicPing != null;
    }

    public void stop() {
        warnIfNotInEventLoop(channel.eventLoop());

        if (periodicPing != null) {
            periodicPing.cancel(false);
        }
        periodicPing = null;
    }

    private void doPeriodicPing() {
        warnIfNotInEventLoop(channel.eventLoop());

        if (lastPingAckTime <= lastPingSendTime - pingTimeoutMillis) {
            channelIsUnhealthy(new PingFailedException("Server did not respond to PING after " +
                                                       (lastPingSendTime - lastPingAckTime) + "ms (limit: " +
                                                       pingTimeoutMillis + "ms)"));
        } else {
            sendPing();
        }
    }

    private void sendPing() {
        channel.writeAndFlush(DEFAULT_PING_FRAME).addListener(res -> {
            if (!res.isSuccess()) {
                log.debug(() -> "Failed to write and flush PING frame to connection", res.cause());
                channelIsUnhealthy(new PingFailedException("Failed to send PING to the service", res.cause()));
            } else {
                lastPingSendTime = System.currentTimeMillis();
            }
        });
    }

    private void channelIsUnhealthy(PingFailedException exception) {
        stop();
        channel.pipeline().fireExceptionCaught(exception);
    }

    private final class PingResponseListener extends SimpleChannelInboundHandler<Http2PingFrame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2PingFrame frame) {
            warnIfNotInEventLoop(channel.eventLoop());

            if (frame.ack()) {
                log.debug(() -> "Received PING ACK from channel " + channel);
                lastPingAckTime = System.currentTimeMillis();
            } else {
                ctx.fireChannelRead(frame);
            }
        }
    }
}
