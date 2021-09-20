/*
 * Copyright 2021 The Netty Project
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
package io.netty.handler.codec.compression;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Compressor that takes care of decompress some input.
 */
public interface Compressor extends AutoCloseable {
    /**
     * This method will read from the input {@link ByteBuf} and compress into a new {@link ByteBuf} that will be
     * allocated (if needed) from the {@link ByteBufAllocator}. This method is expected to read all data from the
     * input but <strong>not</strong> take ownership. The caller is responsible to release the input buffer.
     *
     * This method should be called as long
     *
     * @param input         the {@link ByteBuf} that contains the data to be compressed.
     * @param allocator     the {@link ByteBufAllocator} that is used to allocate a new buffer (if needed) to write the
     *                      compressed bytes too.
     * @return              the {@link ByteBuf} that contains the compressed data. The caller of this method takes
     *                      ownership of the buffer.
     * @throws CompressionException   thrown if an encoding error was encountered.
     */
    ByteBuf compress(ByteBuf input, ByteBufAllocator allocator) throws CompressionException;

    /**
     * By calling this method we signal that the compression stream is marked as finish. The returned {@link ByteBuf}
     * might contain a "trailer" which marks the end of the stream.
     *
     * @return  the {@link ByteBuf} which represent the end of the compression stream, which might be empty if the
     *          compressor don't need a trailer to signal the end.
     */
    ByteBuf finish(ByteBufAllocator allocator);

    /**
     * Returns {@code} true if the decompressor was finished or closed. This might be because someone explicit called
     * {@link #finish(ByteBufAllocator)} or the compressor implementation did decide to close itself due a
     * Compression error which cant be recovered. After {@link #isFinished()} returns {@code true} the
     * {@link #compress(ByteBuf, ByteBufAllocator)} method will just return an empty buffer without consuming anything
     * from its input buffer.
     *
     * @return if the compressor was marked as finished.
     */
    boolean isFinished();

    /**#
     * Close the compressor. After this method was called {@link #isFinished()}
     * will return {@code true} as well.
     */
    @Override
    void close();
}
