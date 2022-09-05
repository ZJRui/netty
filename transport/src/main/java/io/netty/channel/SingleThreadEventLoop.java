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

import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.UnstableApi;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Abstract base class for {@link EventLoop}s that execute all its submitted tasks in a single thread.
 *
 */
public abstract class SingleThreadEventLoop extends SingleThreadEventExecutor implements EventLoop {

    protected static final int DEFAULT_MAX_PENDING_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventLoop.maxPendingTasks", Integer.MAX_VALUE));

    private final Queue<Runnable> tailTasks;

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, threadFactory, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory,
                                    boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, threadFactory, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
        tailTasks = newTaskQueue(maxPendingTasks);
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor,
                                    boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
        tailTasks = newTaskQueue(maxPendingTasks);
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor,
                                    boolean addTaskWakesUp, Queue<Runnable> taskQueue, Queue<Runnable> tailTaskQueue,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, addTaskWakesUp, taskQueue, rejectedExecutionHandler);
        tailTasks = ObjectUtil.checkNotNull(tailTaskQueue, "tailTaskQueue");
    }

    @Override
    public EventLoopGroup parent() {
        return (EventLoopGroup) super.parent();
    }

    @Override
    public EventLoop next() {
        return (EventLoop) super.next();
    }

    @Override
    public ChannelFuture register(Channel channel) {
        /**
         * 注册方法register()在两个地方被调用：一是在端口绑定前，需
         * 要把NioServerSocketChannel注册到Boss线程的Selector上；二是当
         * NioEventLoop监听到有链路接入时，把链路SocketChannel包装成
         * NioSocketChannel ， 并 注 册 到 Woker 线 程 中 。 最 终 调 用
         * NioSocketChannel的辅助对象unsafe的register()方法，unsafe执行
         * 父类AbstractUnsafe的register()模板方法（
         *
         *
         * ）NioEventLoop线程调用AbstractUnsafe.register0()方法，
         * 此方法执行NioServer SocketChannel的doRegister()方法。底层调用
         * ServerSocketChannel 的 register() 方 法 把 Channel 注 册 到 Selector
         * 上，同时带上了附件，此附件为NioServerSocketChannel对象本身。
         * 此处的附件attachment与第（3）步的attr很相似，在后续多路复用器
         * 轮询到事件就绪的SelectionKey时，通过k.attachment获取。当出现
         * 超时或链路未中断或移除时，JVM不会回收此附件。注册成功后，会调
         * 用DefaultChannelPipeline的callHandlerAddedForAllHandlers()方
         * 法，此方法会执行PendingHandlerCallback回调任务，回调原来在没
         * 有注册之前添加的Handler。
         *
         */
        return register(new DefaultChannelPromise(channel, this));
    }

    @Override
    public ChannelFuture register(final ChannelPromise promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        promise.channel().unsafe().register(this, promise);
        return promise;
    }

    @Deprecated
    @Override
    public ChannelFuture register(final Channel channel, final ChannelPromise promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        ObjectUtil.checkNotNull(channel, "channel");
        channel.unsafe().register(this, promise);
        return promise;
    }

    /**
     * Adds a task to be run once at the end of next (or current) {@code eventloop} iteration.
     *
     * @param task to be added.
     */
    @UnstableApi
    public final void executeAfterEventLoopIteration(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        if (isShutdown()) {
            reject();
        }

        if (!tailTasks.offer(task)) {
            reject(task);
        }

        if (!(task instanceof LazyRunnable) && wakesUpForTask(task)) {
            wakeup(inEventLoop());
        }
    }

    /**
     * Removes a task that was added previously via {@link #executeAfterEventLoopIteration(Runnable)}.
     *
     * @param task to be removed.
     *
     * @return {@code true} if the task was removed as a result of this call.
     */
    @UnstableApi
    final boolean removeAfterEventLoopIterationTask(Runnable task) {
        return tailTasks.remove(ObjectUtil.checkNotNull(task, "task"));
    }

    @Override
    protected void afterRunningAllTasks() {
        runAllTasksFrom(tailTasks);
    }

    @Override
    protected boolean hasTasks() {
        return super.hasTasks() || !tailTasks.isEmpty();
    }

    @Override
    public int pendingTasks() {
        return super.pendingTasks() + tailTasks.size();
    }

    /**
     * Returns the number of {@link Channel}s registered with this {@link EventLoop} or {@code -1}
     * if operation is not supported. The returned value is not guaranteed to be exact accurate and
     * should be viewed as a best effort.
     */
    @UnstableApi
    public int registeredChannels() {
        return -1;
    }

    /**
     * @return read-only iterator of active {@link Channel}s registered with this {@link EventLoop}.
     *         The returned value is not guaranteed to be exact accurate and
     *         should be viewed as a best effort. This method is expected to be called from within
     *         event loop.
     * @throws UnsupportedOperationException if operation is not supported by implementation.
     */
    @UnstableApi
    public Iterator<Channel> registeredChannelsIterator() {
        throw new UnsupportedOperationException("registeredChannelsIterator");
    }

    protected static final class ChannelsReadOnlyIterator<T extends Channel> implements Iterator<Channel> {
        private final Iterator<T> channelIterator;

        public ChannelsReadOnlyIterator(Iterable<T> channelIterable) {
            this.channelIterator =
                    ObjectUtil.checkNotNull(channelIterable, "channelIterable").iterator();
        }

        @Override
        public boolean hasNext() {
            return channelIterator.hasNext();
        }

        @Override
        public Channel next() {
            return channelIterator.next();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("remove");
        }

        @SuppressWarnings("unchecked")
        public static <T> Iterator<T> empty() {
            return (Iterator<T>) EMPTY;
        }

        private static final Iterator<Object> EMPTY = new Iterator<Object>() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public Object next() {
                throw new NoSuchElementException();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove");
            }
        };
    }
}
