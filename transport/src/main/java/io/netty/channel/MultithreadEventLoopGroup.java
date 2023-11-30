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
package io.netty.channel;

import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.MultithreadEventExecutorGroup;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Abstract base class for {@link EventLoopGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventLoopGroup extends MultithreadEventExecutorGroup implements EventLoopGroup {

    /**
     *
     *
     * 1. MultithreadEventLoopGroup是NioEventLoopGroup的父类
     *
     * NioEventLoopGroup继承自 MultithreadEventLoopGroup 多提供了两个方法setIoRatio和rebuildSelectors，
     * 一个用于设置NioEventLoop用于IO处理的时间占比，另一个是重新构建Selectors，来处理epoll空轮询导致CPU100%的bug
     *
     *
     */
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(MultithreadEventLoopGroup.class);

    private static final int DEFAULT_EVENT_LOOP_THREADS;

    static {
        /**
         *因此，Netty默认构建的线程数的是电脑线程数的2倍。那它是在什么时候启动的呢？是在“bind”的时候启动的！
         */
        DEFAULT_EVENT_LOOP_THREADS = Math.max(1, SystemPropertyUtil.getInt(
                "io.netty.eventLoopThreads", NettyRuntime.availableProcessors() * 2));

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.eventLoopThreads: {}", DEFAULT_EVENT_LOOP_THREADS);
        }
    }

    /**
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, Executor, Object...)
     */
    protected MultithreadEventLoopGroup(int nThreads, Executor executor, Object... args) {
        /**
         *
         * 这里是一个三元表达式，判断传递过来的参数“nThreads”是否等于0，如果是的话就赋值“DEFAULT_EVENT_LOOP_THREADS”，如果不是的话就赋值为传递过来的值。
         *
         * 那么“DEFAULT_EVENT_LOOP_THREADS”是在什么时候初始化的呢？是在静态代码块中初始化。
         *
         *因此，Netty默认构建的线程数的是电脑线程数的2倍。那它是在什么时候启动的呢？是在“bind”的时候启动的！
         *
         */
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, executor, args);
    }

    /**
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, ThreadFactory, Object...)
     */
    protected MultithreadEventLoopGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, threadFactory, args);
    }

    /**
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, Executor,
     * EventExecutorChooserFactory, Object...)
     */
    protected MultithreadEventLoopGroup(int nThreads, Executor executor, EventExecutorChooserFactory chooserFactory,
                                     Object... args) {
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, executor, chooserFactory, args);
    }

    @Override
    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass(), Thread.MAX_PRIORITY);
    }

    @Override
    public EventLoop next() {
        /**
         * NioEventLoopgroup 通过next方法获取NioEventLoop线程，最终会调用其父类MultiThreadEventExecutorGroup的next方法。
         * 委托父类的选择器EventExecutorChooser。具体使用那种选择器对象取决于 MultiThreadEventExecutorGroup的构造方法中
         * 使用的策略模式。
         * 根据线程条数是否为2的幂次来选择策略，若是，则选择器为
         * PowerOfTwoEventExecutorChooser，其选择策略使用与运算计算下一
         * 个选择的线程组的下标index，此计算方法在第7章中也有相似的应
         * 用；若不是，则选择器为GenericEventExecutorChooser，其选择策略
         * 为使用求余的方法计算下一个线程在线程组中的下标index。其中，
         * PowerOfTwoEventExecutorChooser选择器的与运算性能会更好。
         */
        return (EventLoop) super.next();
    }

    @Override
    protected abstract EventLoop newChild(Executor executor, Object... args) throws Exception;

    @Override
    public ChannelFuture register(Channel channel) {
        /**
         * 选择一个NioEventLoop来 register这个channel
         */
        return next().register(channel);
    }

    @Override
    public ChannelFuture register(ChannelPromise promise) {
        return next().register(promise);
    }

    @Deprecated
    @Override
    public ChannelFuture register(Channel channel, ChannelPromise promise) {
        return next().register(channel, promise);
    }

}
