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

import com.aayushatharva.brotli4j.encoder.Encoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.internal.ObjectUtil;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Compress a {@link ByteBuf} with the brotli format.
 *
 * See <a href="https://github.com/google/brotli">brotli</a>.
 */
public final class BrotliCompressor implements Compressor {
    private final Encoder.Parameters parameters;
    private boolean closed;

    /**
     * Create a new {@link BrotliCompressor} Instance
     *
     * @param parameters {@link Encoder.Parameters} Instance
     */
    private BrotliCompressor(Encoder.Parameters parameters) {
        this.parameters = ObjectUtil.checkNotNull(parameters, "Parameters");
    }

    /**
     * Create a new {@link BrotliCompressor} factory
     *
     * @param parameters {@link Encoder.Parameters} Instance
     */
    public static Supplier<BrotliCompressor> newFactory(Encoder.Parameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        return () -> new BrotliCompressor(parameters);
    }

    /**
     * Create a new {@link BrotliCompressor} factory
     *
     * @param brotliOptions {@link BrotliOptions} to use.
     */
    public static Supplier<BrotliCompressor> newFactory(BrotliOptions brotliOptions) {
        return newFactory(brotliOptions.parameters());
    }

    /**
     * Create a new {@link BrotliCompressor} factory
     *
     * with {@link Encoder.Parameters#setQuality(int)} set to 4
     * and {@link Encoder.Parameters#setMode(Encoder.Mode)} set to {@link Encoder.Mode#TEXT}
     */
    public static Supplier<BrotliCompressor> newFactory() {
        return newFactory(BrotliOptions.DEFAULT);
    }

    @Override
    public ByteBuf compress(ByteBuf input, ByteBufAllocator allocator) throws CompressionException {
        if (closed || !input.isReadable()) {
            return Unpooled.EMPTY_BUFFER;
        }
        byte[] uncompressed = ByteBufUtil.getBytes(input, input.readerIndex(), input.readableBytes(), false);
        try {
            byte[] compressed = Encoder.compress(uncompressed, parameters);
            input.skipBytes(input.readableBytes());
            return Unpooled.wrappedBuffer(compressed);
        } catch (IOException e) {
            closed = true;
            throw new CompressionException(e);
        }
    }

    @Override
    public ByteBuf finish(ByteBufAllocator allocator) {
        closed = true;
        return Unpooled.EMPTY_BUFFER;
    }

    @Override
    public boolean isFinished() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }

}
