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
package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    // The order in which child ChannelOptions are applied is important they may depend on each other for validation
    // purposes.
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();
    private final Map<AttributeKey<?>, Object> childAttrs = new ConcurrentHashMap<AttributeKey<?>, Object>();
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this);
    private volatile EventLoopGroup childGroup;
    private volatile ChannelHandler childHandler;

    public ServerBootstrap() {
    }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        childAttrs.putAll(bootstrap.childAttrs);
    }

    /**
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        super.group(parentGroup);
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = ObjectUtil.checkNotNull(childGroup, "childGroup");
        return this;
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        ObjectUtil.checkNotNull(childOption, "childOption");
        synchronized (childOptions) {
            if (value == null) {
                childOptions.remove(childOption);
            } else {
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    /**
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        ObjectUtil.checkNotNull(childKey, "childKey");
        if (value == null) {
            childAttrs.remove(childKey);
        } else {
            childAttrs.put(childKey, value);
        }
        return this;
    }

    /**
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
        return this;
    }

    @Override
    void init(Channel channel) {
        /**
         * ）初始化NioServerSocketChannel、设置属性attr和参数
         * option，并把Handler预添加到NioServerSocketChannel的Pipeline管
         * 道中。其中，attr是绑定在每个Channel上的上下文；option一般用来
         * 设置一些Channel的参数；NioServerSocketChannel上的Handler除了
         * 包括用户自定义的，还会加上ServerBootstrapAcceptor。
         */
        setChannelOptions(channel, newOptionsArray(), logger);
        setAttributes(channel, newAttributesArray());

        ChannelPipeline p = channel.pipeline();

        final EventLoopGroup currentChildGroup = childGroup;
        final ChannelHandler currentChildHandler = childHandler;
        final Entry<ChannelOption<?>, Object>[] currentChildOptions = newOptionsArray(childOptions);
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs = newAttributesArray(childAttrs);

        /**
         * ChannelInitializer是一个ChannelHandler，但它不处理任何出站、入站事件，其目的只为了完成Channel的初始化。
         *
         * 当ChannelHandler被添加到ChannelPipeline后，会触发一个handlerAdded方法回调，本质是在addLast方法中调用了  callHandlerAdded0(newCtx);
         * 然后  callHandlerAdded0(newCtx); 调用handlerAdded
         * 这个方法里会调用initChannel()进行初始化，初始化完成后会将自己从Pipeline中删除，
         */
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) {
                /**
                 * 1. initChannel方法 什么时候被调用？
                 * io.netty.bootstrap.AbstractBootstrap#initAndRegister()
                 * io.netty.bootstrap.AbstractBootstrap#doBind(java.net.SocketAddress)
                 * io.netty.bootstrap.AbstractBootstrap#bind()
                 */
                final ChannelPipeline pipeline = ch.pipeline();
                /**
                 *
                 * 我们再看回ServerBootstrapAcceptor类，其中在向ChannelPipeline中添加ChannelInitializer时，在
                 * initChannel方法中会向ChannelPipeline中添加一个ServerBootstrap.handler()。这是个用户自定义
                 * 的ChannelHandler，如果用户没有设置，就不会通过判空校验，也就不会添加到ChannelPipeline中。
                 */
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    pipeline.addLast(handler);
                }


                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        /**
                         * 在处理完用户自定义的ChannelHandler后，还会再添加一个ServerBootstrapAcceptor。
                         *
                         */
                        pipeline.addLast(new ServerBootstrapAcceptor(
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                    }
                });
            }
        });
    }

    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {
        /**
         *
         * 1.在Netty服务端启动时，会调用ServerBootstrap.bind()绑定本地端口用来监听客户端的连接。
         * 而这个方法会通过反射创建ServerSocketChannel并初始化，ServerBootstrap.init()会初始化ServerSocketChannel，
         * 将ServerBootstrapAcceptor添加到服务端Channel的Pipeline中。
         *
         * 2.ServerBootstrapAcceptor实现了ChannelInboundHandler接口，而
         * 是ChannelInboundHandler本身就是进站处理器，处理channel进站的一系列事件
         *
         * ServerBootstrapAcceptor类重写了channelRead方法，这是它最主要的方法
         * 在channelRead方法中，整个过程如下：
         *
         * 设置SocketChannel的Pipeline。
         * 设置ChannelOptions和Attributes。
         * 将Channel注册到WorkerGroup中。
         * 将Channel注册到workGroup中。
         *
         * 如果整个过程出现了异常，Netty会调用forceClose()强制关闭连接，其底层是调用了SocketChannel.close()方法关闭连接。
         *
         * 3.ServerBootstrapAcceptor是一个特殊的ChannelHandler，它是Netty服务端用来接收客户端连接的核心类。'
         * ServerBootstrap在初始化ServerSocketChannel时，会往它的Pipeline中添加ServerBootstrapAcceptor，
         * ServerBootstrapAcceptor重写了ChannelRead回调，当NioEventLoop检测到有OP_ACCEPT事件到达时会执
         * 行NioMessageUnsafe.read()方法，它会调用JDK底层的API接收客户端连接，并把它作为msg触发ChannelRead回调，
         * 这样ServerBootstrapAcceptor就可以拿到客户端连接，帮助它进行初始化并注册到WorkerGroup中。
         *
         * 4. Boss中的NioEventLoop 执行selector.slectNow()，当serverSocketChannel发生OP_Accept事件的时候就会返回
         * 对于OP_Accept和OP_read事件，Netty都是使用  unsafe.read();来处理（具体查看NioEventLoop.processSelectedKey()中对事件的处理）
         *
         * 对于服务端的Unsafe.read()，这里会执行io.netty.channel.nio.
         * AbstractNioMessageChannel.NioMessageUnsafe.read()方法，它会调用JDK底层的ServerSocketChannel.accept()
         * 接收到客户端的连接后，将其封装成Netty的NioSocketChannel，再通过Pipeline将ChannelRead事件传播出去( pipeline.fireChannelRead(clientSocketChannel);)，
         * 这样ServerBootstrapAcceptor就可以在ChannelRead回调里处理新的客户端连接了。
         *
         *
         */

        /**
         * Reactor模型中的WorkerGroup
         */
        private final EventLoopGroup childGroup;
        /**
         * 客户端Channel的ChannelHandler
         */
        private final ChannelHandler childHandler;

        /**
         * 客户端Channel的Options
         */
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        /**
         * 客户端Channel的Attrs
         */
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        /**
         * 启用自动读取的任务
         */
        private final Runnable enableAutoReadTask;

        ServerBootstrapAcceptor(
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;

            // Task which is scheduled to re-enable auto-read.
            // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
            // not be able to load the class because of the file limit it already reached.
            //
            // See https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            /**
             *
             * 1.ServerBootstrapAcceptor实现了ChannelInboundHandler接口，而
             * 是ChannelInboundHandler本身就是进站处理器，处理channel进站的一系列事件
             *
             * ServerBootstrapAcceptor类重写了channelRead方法，这是它最主要的方法
             * 在channelRead方法中，整个过程如下：
             *
             * 设置SocketChannel的Pipeline。
             * 设置ChannelOptions和Attributes。
             * 将Channel注册到WorkerGroup中。
             * 将Channel注册到workGroup中。=================》这里是重点
             *
             * 如果整个过程出现了异常，Netty会调用forceClose()强制关闭连接，其底层是调用了SocketChannel.close()方法关闭连接。
             *
             * 2.什么时候会触发channelRead方法
             * 当有新客户端连接时，就会触发ServerBootstrapAcceptor类的channelRead方法
             *
             * 当调用ServerBootstrap.bind()方法时，Netty会创建ServerSocketChannel，并把它注册到BossGroup的NioEventLoop
             * 的Selector多路复用器，最后再绑定到本地端口。
             *
             *
             * 这样Netty就可以接收客户端的连接了，当有新的连接接入时，Selector会监听到并返回准备就绪的Channel，
             * NioEventLoop会处理这些事件，详见NioEventLoop.processSelectedKey()方法。
             *
             *由于事件类型是OP_ACCEPT，因此会调用Unsafe.read()处理，源码如下：
             *  // 数据可读、有新的连接接入
             * if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
             *     // 对于ServerSocketChannel只关心OP_ACCEPT事件
             *     unsafe.read();
             * }
             *
             * 这个Unsafe接口有两大实现，分别是服务端Channel的Unsafe和客户端Channel的Unsafe。
             * 前者的read负责接收SocketChannel连接，后者的read负责读取对端发送的数据并封装成ByteBuf。
             *
             *
             * 对于服务端的Unsafe.read()，这里会执行io.netty.channel.nio.AbstractNioMessageChannel.NioMessageUnsafe.read()方法，
             * 它会调用JDK底层的ServerSocketChannel.accept()接收到客户端的连接后，将其封装成Netty的NioSocketChannel，
             * 再通过Pipeline将ChannelRead事件传播出去，这样ServerBootstrapAcceptor就可以在ChannelRead回调里处理新的客户端连接了。
             *
             * ServerBootstrapAcceptor是一个特殊的ChannelHandler，它是Netty服务端用来接收客户端连接的核心类。'
             * ServerBootstrap在初始化ServerSocketChannel时，会往它的Pipeline中添加ServerBootstrapAcceptor，
             * ServerBootstrapAcceptor重写了ChannelRead回调，当NioEventLoop检测到有OP_ACCEPT事件到达时会执
             * 行NioMessageUnsafe.read()方法，它会调用JDK底层的API接收客户端连接，并把它作为msg触发ChannelRead回调，
             * 这样ServerBootstrapAcceptor就可以拿到客户端连接，帮助它进行初始化并注册到WorkerGroup中。
             */
            final Channel child = (Channel) msg;

            child.pipeline().addLast(childHandler);

            setChannelOptions(child, childOptions, logger);
            setAttributes(child, childAttrs);

            try {
                /**
                 * ServerBootstrapAcceptor的源码很简单，只有不到小一百行，它的职责也很简单，只负责处理客户端新的连接建立，并把连接注册到WorkerGroup中，仅此而已。
                 * 什么时候会触发channelRead方法？ 查看 上文解释
                 */
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        synchronized (childOptions) {
            return copiedMap(childOptions);
        }
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
