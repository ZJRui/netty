/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.IntSupplier;

/**
 * Default select strategy.
 */
final class DefaultSelectStrategy implements SelectStrategy {
    static final SelectStrategy INSTANCE = new DefaultSelectStrategy();

    private DefaultSelectStrategy() { }

    @Override
    public int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception {
        /**
         *  若 taskQueue 队 列 中 有 任 务 ， 且 从 EventLoop 线 程 进 入
         * select()方法开始后，一直无其他线程触发唤醒动作，则需要调用
         * selectNow() 方 法 ， 并 立 刻 返 回 。 因 为 在 运 行 select(boolean
         * oldWakenUp) 之 前 ， 若 有 线 程 触 发 了 wakeUp 动 作 ， 则 需 要 保 证
         * tsakQueue队列中的任务得到了及时处理，防止等待timeoutMillis超
         * 时后处理。
         */
        return hasTasks ? selectSupplier.get() : SelectStrategy.SELECT;
    }
}
