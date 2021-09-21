[ Home](https://howtodoinjava.com/) / [Java](https://howtodoinjava.com/java/) / [Java New Input/Output](https://howtodoinjava.com/java/nio/) / Java NIO Read File Example

# Java NIO Read File Example

Last Modified: August 19, 2020

Learn to read a file from the filesystem using the Java NIO APIs i.e using buffer, channel, and path classes.

## 1. FileChannel and ByteBuffer to read small files

Use this technique to read a small file where all the file content is fits into the buffer, and the file can be read in a single operation.

#### Example 1: Java read small file using ByteBuffer and RandomAccessFile

```java
package com.howtodoinjava.test.nio;
 
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
 
public class ReadFileWithFileSizeBuffer
{
    public static void main(String args[])
    {
        try
        {
            RandomAccessFile aFile = new RandomAccessFile("test.txt","r");
 
            FileChannel inChannel = aFile.getChannel();
            long fileSize = inChannel.size();
 			//这里直接指定文件的大小作为 ByteBuffer的大小
            ByteBuffer buffer = ByteBuffer.allocate((int) fileSize);
            inChannel.read(buffer);
            buffer.flip();
 
            //Verify the file content
            for (int i = 0; i < fileSize; i++)
            {
                System.out.print((char) buffer.get());
            }
 
            inChannel.close();
            aFile.close();
        } 
        catch (IOException exc)
        {
            System.out.println(exc);
            System.exit(1);
        }
    }
}
```

## 2. FileChannel and ByteBuffer to read large files

Use this technique to read the large file where all the file content will not fit into the buffer at a time, the buffer size will be needed of some very big number. In this case, we can read the file in chunks with a fixed size small buffer.

#### Example 2: Java read a large file in chunks with fixed-size buffer

```java
package com.howtodoinjava.test.nio;
 
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
 
public class ReadFileWithFixedSizeBuffer 
{
    public static void main(String[] args) throws IOException 
    {
        RandomAccessFile aFile = new RandomAccessFile("test.txt", "r");
 
        FileChannel inChannel = aFile.getChannel();
 
 		//这里指定ByteBuffer为固定大小，然后 下面使用while循环读到byteBuffer 中
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        while(inChannel.read(buffer) > 0)
        {
            buffer.flip();
            for (int i = 0; i < buffer.limit(); i++)
            {
                System.out.print((char) buffer.get());
            }
            buffer.clear(); // do something with the data and clear/compact it.
        }
 
        inChannel.close();
        aFile.close();
    }
}

```

## 3. Read a file using MappedByteBuffer

`MappedByteBuffer` extends the `ByteBuffer` class with operations that are specific to memory-mapped file regions.

#### Example 3: Reading a file using memory-mapped files in Java

```java
package com.howtodoinjava.test.nio;
 
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
 
public class ReadFileWithMappedByteBuffer 
{
    public static void main(String[] args) throws IOException 
    {
        RandomAccessFile aFile = new RandomAccessFile("test.txt", "r");
 
        FileChannel inChannel = aFile.getChannel();
        //这里使用MappedByteBuffer，实际的对象类型为DirectByteBuffer
        MappedByteBuffer buffer = inChannel.map(FileChannel.MapMode.READ_ONLY, 0, inChannel.size());
 
        buffer.load();  
        for (int i = 0; i < buffer.limit(); i++)
        {
            System.out.print((char) buffer.get());
        }
        buffer.clear(); // do something with the data and clear/compact it.
 
        inChannel.close();
        aFile.close();
    }
}
```

All the above techniques will read the content of the file and print it to the console.