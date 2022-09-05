/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.socket.nio;

import io.netty.channel.ChannelException;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.util.internal.SocketUtils;
import io.netty.channel.nio.AbstractNioMessageChannel;
import io.netty.channel.socket.DefaultServerSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannelConfig;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SuppressJava6Requirement;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.List;
import java.util.Map;

/**
 * A {@link io.netty.channel.socket.ServerSocketChannel} implementation which uses
 * NIO selector based implementation to accept new connections.
 */
@SuppressWarnings("all")
public class NioServerSocketChannel extends AbstractNioMessageChannel
                             implements io.netty.channel.socket.ServerSocketChannel {

    /**
     * NioServerSocketChannel是AbstractNioMessageChannel的子类，
     * 由于NioServerSocketChannel由服务端使用，并且只负责监听Socket
     * 的接入，不关心I/O的读/写，所以与NioSocketChannel相比要简单很
     * 多。它封装了NIO中的ServerSocketChannel，并通过newSocket()方法
     * 打 开 ServerSocket  Channel
     *
     * 重点关注它是如何监听新加入的连接的（需要由
     * doReadMessages()方法来完成）
     */
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioServerSocketChannel.class);

    private static final Method OPEN_SERVER_SOCKET_CHANNEL_WITH_FAMILY =
            SelectorProviderUtil.findOpenMethod("openServerSocketChannel");

    private static ServerSocketChannel newChannel(SelectorProvider provider, InternetProtocolFamily family) {
        try {
            ServerSocketChannel channel =
                    SelectorProviderUtil.newChannel(OPEN_SERVER_SOCKET_CHANNEL_WITH_FAMILY, provider, family);
            return channel == null ? provider.openServerSocketChannel() : channel;
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    private final ServerSocketChannelConfig config;

    /**
     * Create a new instance
     */
    public NioServerSocketChannel() {
        /**
         *
         * NioServerSocketChannel 不会被直接new 而是通过反射来创建 ，我们一般只会指定使用的时NioServerSocketChannel 还是NioSocketChanne
         *      ServerBootstrap b = new ServerBootstrap();
         *             b.group(bossGroup, workerGroup)
         *              .channel(NioServerSocketChannel.class)
         *              .option(ChannelOption.SO_BACKLOG, 100)
         *              .handler(new LoggingHandler(LogLevel.INFO))
         *
         */
        this(DEFAULT_SELECTOR_PROVIDER);
    }

    /**
     * Create a new instance using the given {@link SelectorProvider}.
     */
    public NioServerSocketChannel(SelectorProvider provider) {
        this(provider, null);
    }

    /**
     * Create a new instance using the given {@link SelectorProvider} and protocol family (supported only since JDK 15).
     */
    public NioServerSocketChannel(SelectorProvider provider, InternetProtocolFamily family) {
        this(newChannel(provider, family));
    }

    /**
     * Create a new instance using the given {@link ServerSocketChannel}.
     */
    public NioServerSocketChannel(ServerSocketChannel channel) {
        /**
         *
         * 注意 NioServerSocketChannel  的构造器中 自己 指定了 自己 只对Op_accept
         * 事件感兴趣，这个 参数op_accept 被复制给 属性  readInterestOp
         *
         * 然后在  io.netty.bootstrap.AbstractBootstrap#initAndRegister()
         * 方法中 会触发   ChannelFuture regFuture = config().group().register(channel);
         * 也就是 JDK的 ServerSocketChannel注册到 selector对象上，
         * 最终这个 注册 会执行 io.netty.channel.AbstractChannel.AbstractUnsafe#register0
         *
         * 在unsafe的register0 的doRegister 中 注册的时候 并没有 将 NioServerSocketChannel的 readInterestOp 设置到Selector对象上。
         *
         * 而是在 register0的   beginRead()--->io.netty.channel.nio.AbstractNioMessageChannel#doBeginRead()-->
         * io.netty.channel.nio.AbstractNioChannel#doBeginRead()  这个方法中 才将 NioServerSocketChannel的readInterestOp 注册到 Selector
         *
         *
         *
         * 问题：  ServerSocketChannel在注册到Selector上后为何要等到绑定端口才设置监听OP_ACCEPT事件？提示：跟Netty的事件触发模型有关。（Netty源码剖析与应用）
         *
         */
        super(null, channel, SelectionKey.OP_ACCEPT);
        config = new NioServerSocketChannelConfig(this, javaChannel().socket());
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public ServerSocketChannelConfig config() {
        return config;
    }

    @Override
    public boolean isActive() {
        // As java.nio.ServerSocketChannel.isBound() will continue to return true even after the channel was closed
        // we will also need to check if it is open.
        return isOpen() && javaChannel().socket().isBound();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return null;
    }

    @Override
    protected ServerSocketChannel javaChannel() {
        return (ServerSocketChannel) super.javaChannel();
    }

    @Override
    protected SocketAddress localAddress0() {
        return SocketUtils.localSocketAddress(javaChannel().socket());
    }

    @SuppressJava6Requirement(reason = "Usage guarded by java version check")
    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().bind(localAddress, config.getBacklog());
        } else {
            javaChannel().socket().bind(localAddress, config.getBacklog());
        }
    }

    @Override
    protected void doClose() throws Exception {
        javaChannel().close();
    }

    @Override
    protected int doReadMessages(List<Object> buf) throws Exception {
        /**
         * 重点关注它是如何监听新加入的连接的（需要由
         * doReadMessages()方法来完成）
         */
        //调用ServerSocketChannel的accept方法监听新加入的连接
        SocketChannel ch = SocketUtils.accept(javaChannel());

        try {
            if (ch != null) {
                /**
                 * 每个 新连接都会构造一个NioSocketChannel
                 */
                buf.add(new NioSocketChannel(this, ch));
                return 1;
            }
        } catch (Throwable t) {
            logger.warn("Failed to create a new channel from an accepted socket.", t);

            try {
                ch.close();
            } catch (Throwable t2) {
                logger.warn("Failed to close a socket.", t2);
            }
        }

        return 0;
    }

    // Unnecessary stuff
    @Override
    protected boolean doConnect(
            SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doFinishConnect() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return null;
    }

    @Override
    protected void doDisconnect() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) throws Exception {
        throw new UnsupportedOperationException();
    }

    private final class NioServerSocketChannelConfig extends DefaultServerSocketChannelConfig {
        private NioServerSocketChannelConfig(NioServerSocketChannel channel, ServerSocket javaSocket) {
            super(channel, javaSocket);
        }

        @Override
        protected void autoReadCleared() {
            clearReadPending();
        }

        @Override
        public <T> boolean setOption(ChannelOption<T> option, T value) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.setOption(jdkChannel(), (NioChannelOption<T>) option, value);
            }
            return super.setOption(option, value);
        }

        @Override
        public <T> T getOption(ChannelOption<T> option) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.getOption(jdkChannel(), (NioChannelOption<T>) option);
            }
            return super.getOption(option);
        }

        @Override
        public Map<ChannelOption<?>, Object> getOptions() {
            if (PlatformDependent.javaVersion() >= 7) {
                return getOptions(super.getOptions(), NioChannelOption.getOptions(jdkChannel()));
            }
            return super.getOptions();
        }

        private ServerSocketChannel jdkChannel() {
            return ((NioServerSocketChannel) channel).javaChannel();
        }
    }

    // Override just to to be able to call directly via unit tests.
    @Override
    protected boolean closeOnReadError(Throwable cause) {
        return super.closeOnReadError(cause);
    }
}
