/*
 * Copyright 2013 The Netty Project
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
package io.netty.util.concurrent;

import io.netty.util.internal.ObjectUtil;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

public final class ThreadPerTaskExecutor implements Executor {
    private final ThreadFactory threadFactory;

    public ThreadPerTaskExecutor(ThreadFactory threadFactory) {
        this.threadFactory = ObjectUtil.checkNotNull(threadFactory, "threadFactory");
    }

    @Override
    public void execute(Runnable command) {
        /**
         * 这个线程池的实现的任务调度很特殊，对每一个提交进来的任务 他都会创建一个新的线程 来执行这个任务。
         *
         * NioEventLoop对象 中通过父类SingleThreadEventExecutor的executor属性持有一个  ThreadPerTaskExecutor 对象
         *
         * 然后在SingleEvent Executor的 doStartThread 方法中 会使用 ThreadPerTaskExecutor线程池的execute方法来执行一个任务。
         * 这个任务的逻辑 就是 处理NioEventLoop对象的任务队列中的任务。
         *
         *  ThreadPerTaskExecutor对提交任务的调度就是启动新的线程 来执行任务。
         *
         *
         *
         *  2. 这里的newThread最终会创建
         *   new FastThreadLocalThread(threadGroup, r, name);
         *    Netty 的 NioEventLoop 线 程 被 包 装 成 了FastThreadLocalThread线程，
         */
        threadFactory.newThread(command).start();
    }
}
