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

import static software.amazon.awssdk.http.nio.netty.internal.utils.NettyUtils.doInEventLoop;

import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelPool;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.util.function.Function;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.nio.netty.internal.ChannelAttributeKey;

/**
 * A {@link ChannelPool} that will send periodic HTTP PINGs across all channels checked out, once they've had their protocols
 * negotiated to be {@link Protocol#HTTP2}.
 *
 * If the service does not respond to these PINGs after a configurable amount of time, the channels will be closed.
 */
@SdkInternalApi
final class Http2PingedChannelPool implements ChannelPool {
    private static final AttributeKey<Http2ChannelHealthChecker> HEALTH_CHECKER =
        AttributeKey.newInstance("software.amazon.awssdk.http.nio.netty.internal.http2.Http2ChannelHealthChecker");

    private final ChannelPool delegate;
    private final Function<Channel, Http2ChannelHealthChecker> healthCheckerFactory;

    Http2PingedChannelPool(ChannelPool delegate, Function<Channel, Http2ChannelHealthChecker> healthCheckerFactory) {
        this.delegate = delegate;
        this.healthCheckerFactory = healthCheckerFactory;
    }

    @Override
    public Future<Channel> acquire() {
        Future<Channel> acquire = delegate.acquire();
        return acquire.addListener(f -> tryStartPinging(acquire));
    }

    @Override
    public Future<Channel> acquire(Promise<Channel> promise) {
        Future<Channel> acquire = delegate.acquire(promise);
        return acquire.addListener(f -> tryStartPinging(acquire));
    }

    @Override
    public Future<Void> release(Channel channel) {
        return delegate.release(channel);
    }

    @Override
    public Future<Void> release(Channel channel, Promise<Void> promise) {
        return delegate.release(channel, promise);
    }

    private void tryStartPinging(Future<Channel> promise) {
        if (promise.isSuccess()) {
            Channel channel = promise.getNow();
            channel.attr(ChannelAttributeKey.PROTOCOL_FUTURE).get()
                   .thenAccept(p -> startPinging(p, channel));
        }
    }

    private void startPinging(Protocol protocol, Channel channel) {
        if (protocol == Protocol.HTTP2) {
            Http2ChannelHealthChecker healthChecker = healthCheckerFactory.apply(channel);
            channel.attr(HEALTH_CHECKER).set(healthChecker);
            doInEventLoop(channel.eventLoop(), healthChecker::start);
        }
    }

    @Override
    public void close() {
        delegate.close();
    }
}
