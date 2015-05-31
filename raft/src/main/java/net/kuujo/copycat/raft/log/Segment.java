/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.raft.log;

import net.kuujo.copycat.io.Buffer;
import net.kuujo.copycat.io.serializer.Serializer;
import net.kuujo.copycat.raft.log.entry.Entry;
import net.kuujo.copycat.util.ExecutionContext;

import java.util.ConcurrentModificationException;

/**
 * Log segment.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class Segment implements AutoCloseable {
  private static final int ENTRY_TYPE_SIZE = 1;
  private static final int ENTRY_MODE_SIZE = 1;
  private static final int ENTRY_TERM_SIZE = 8;

  /**
   * Opens a new segment.
   *
   * @param buffer The segment buffer.
   * @param descriptor The segment descriptor.
   * @param index The segment index.
   * @param context The segment execution context.
   * @return The opened segment.
   */
  public static Segment open(Buffer buffer, SegmentDescriptor descriptor, OffsetIndex index, ExecutionContext context) {
    return new Segment(buffer, descriptor, index, context);
  }

  private final SegmentDescriptor descriptor;
  private final Serializer serializer;
  private final Buffer source;
  private final Buffer writeBuffer;
  private final Buffer readBuffer;
  private final OffsetIndex offsetIndex;
  private long skip = 0;
  private boolean open = true;

  Segment(Buffer buffer, SegmentDescriptor descriptor, OffsetIndex offsetIndex, ExecutionContext context) {
    if (buffer == null)
      throw new NullPointerException("buffer cannot be null");
    if (descriptor == null)
      throw new NullPointerException("descriptor cannot be null");
    if (offsetIndex == null)
      throw new NullPointerException("index cannot be null");
    if (context == null)
      throw new NullPointerException("context cannot be null");

    this.source = buffer;
    this.serializer = context.serializer();
    this.writeBuffer = buffer.slice();
    this.readBuffer = writeBuffer.asReadOnlyBuffer();
    this.descriptor = descriptor;
    this.offsetIndex = offsetIndex;
    if (offsetIndex.size() > 0) {
      writeBuffer.position(offsetIndex.position(offsetIndex.lastOffset()) + offsetIndex.length(offsetIndex.lastOffset()));
    }
  }

  /**
   * Returns the segment descriptor.
   *
   * @return The segment descriptor.
   */
  public SegmentDescriptor descriptor() {
    return descriptor;
  }

  /**
   * Returns a boolean value indicating whether the segment is open.
   *
   * @return Indicates whether the segment is open.
   */
  public boolean isOpen() {
    return open;
  }

  /**
   * Returns a boolean value indicating whether the segment is empty.
   *
   * @return Indicates whether the segment is empty.
   */
  public boolean isEmpty() {
    return offsetIndex.size() == 0;
  }

  /**
   * Returns a boolean value indicating whether the segment is full.
   *
   * @return Indicates whether the segment is full.
   */
  public boolean isFull() {
    return size() >= descriptor.maxSegmentSize();
  }

  /**
   * Returns a boolean value indicating whether the segment is immutable.
   *
   * @return Indicates whether the segment is immutable.
   */
  public boolean isLocked() {
    return descriptor.locked();
  }

  /**
   * Returns the total size of the segment in bytes.
   *
   * @return The size of the segment in bytes.
   */
  public long size() {
    return writeBuffer.offset() + writeBuffer.position();
  }

  /**
   * Returns the current range of the segment.
   *
   * @return The current range of the segment.
   */
  public long length() {
    return offsetIndex.lastOffset() + 1;
  }

  /**
   * Returns the index of the first entry in the segment.
   *
   * @return The index of the first entry in the segment or {@code 0} if the segment is empty.
   */
  public long firstIndex() {
    if (!isOpen())
      throw new IllegalStateException("segment not open");
    return !isEmpty() ? descriptor.index() : 0;
  }

  /**
   * Returns the index of the last entry in the segment.
   *
   * @return The index of the last entry in the segment or {@code 0} if the segment is empty.
   */
  public long lastIndex() {
    if (!isOpen())
      throw new IllegalStateException("segment not open");
    return !isEmpty() ? offsetIndex.lastOffset() + descriptor.index() : 0;
  }

  /**
   * Returns the next index in the segment.
   *
   * @return The next index in the segment.
   */
  public long nextIndex() {
    return !isEmpty() ? lastIndex() + skip + 1 : descriptor.index() + skip;
  }

  /**
   * Returns the offset for the given index.
   */
  private int offset(long index) {
    return (int) (index - descriptor.index());
  }

  /**
   * Checks the range of the given index.
   */
  private void checkRange(long index) {
    if (isEmpty())
      throw new IndexOutOfBoundsException("segment is empty");
    if (index < firstIndex())
      throw new IndexOutOfBoundsException(index + " is less than the first index in the segment");
    if (index > lastIndex())
      throw new IndexOutOfBoundsException(index + " is greater than the last index in the segment");
  }

  /**
   * Returns the absolute entry position for the entry at the given position.
   */
  private long entryPosition(long position) {
    return position + ENTRY_TYPE_SIZE + ENTRY_MODE_SIZE + ENTRY_TERM_SIZE;
  }

  /**
   * Commits an entry to the segment.
   */
  public long appendEntry(Entry entry) {
    long nextIndex = nextIndex();

    if (entry.getIndex() < nextIndex) {
      throw new CommitModificationException("cannot modify committed entry");
    }

    if (entry.getIndex() > nextIndex) {
      throw new ConcurrentModificationException("attempt to commit entry with non-monotonic index");
    }

    // Record the starting position of the new entry.
    long position = writeBuffer.position();

    // Write the entry type identifier and 16-bit signed key size.
    serializer.writeObject(entry, writeBuffer.limit(-1));

    int totalLength = (int) (writeBuffer.position() - position);
    offsetIndex.index(offset(entry.getIndex()), position, totalLength);

    return entry.getIndex();
  }

  /**
   * Reads the entry at the given index.
   *
   * @param index The index from which to read the entry.
   * @return The entry at the given index.
   */
  public <T extends Entry<T>> T getEntry(long index) {
    if (!isOpen())
      throw new IllegalStateException("segment not open");
    checkRange(index);

    // Get the offset of the index within this segment.
    int offset = offset(index);

    // Get the start position of the offset from the offset index.
    long position = offsetIndex.position(offset);

    // If the position is -1 then that indicates no start position was found. The offset may have been removed from
    // the index via deduplication or compaction.
    if (position != -1) {

      // Get the length of the offset entry from the offset index. This will be calculated by getting the start
      // position of the next offset in the index and subtracting this position from the next position.
      int length = offsetIndex.length(offset);

      // Deserialize the entry from a slice of the underlying buffer.
      long entryPosition = entryPosition(position);
      try (Buffer value = readBuffer.slice(entryPosition, length - (entryPosition - position))) {
        return serializer.readObject(value);
      }
    }
    return null;
  }

  /**
   * Returns a boolean value indicating whether the given index is within the range of the segment.
   *
   * @param index The index to check.
   * @return Indicates whether the given index is within the range of the segment.
   */
  public boolean containsIndex(long index) {
    if (!isOpen())
      throw new IllegalStateException("segment not open");
    return !isEmpty() && index >= descriptor.index() && index <= lastIndex();
  }

  /**
   * Returns a boolean value indicating whether the entry at the given index is active.
   *
   * @param index The index to check.
   * @return Indicates whether the entry at the given index is active.
   */
  public boolean containsEntry(long index) {
    if (!isOpen())
      throw new IllegalStateException("segment not open");
    return containsIndex(index) && offsetIndex.contains(offset(index));
  }

  /**
   * Skips a number of entries in the segment.
   *
   * @param entries The number of entries to skip.
   * @return The segment.
   */
  public Segment skip(long entries) {
    if (!isOpen())
      throw new IllegalStateException("segment not open");
    this.skip += entries;
    return this;
  }

  /**
   * Truncates entries after the given index.
   *
   * @param index The index after which to remove entries.
   * @return The segment.
   */
  public Segment truncate(long index) {
    if (!isOpen())
      throw new IllegalStateException("segment not open");
    int offset = offset(index);
    if (offset < offsetIndex.lastOffset()) {
      int diff = offsetIndex.lastOffset() - offset;
      skip = Math.max(skip - diff, 0);
      offsetIndex.truncate(offset);
      offsetIndex.flush();
    }
    return this;
  }

  /**
   * Flushes the segment buffers to disk.
   *
   * @return The segment.
   */
  public Segment flush() {
    writeBuffer.flush();
    offsetIndex.flush();
    return this;
  }

  @Override
  public void close() {
    readBuffer.close();
    writeBuffer.close();
    source.close();
    offsetIndex.close();
    open = false;
  }

  /**
   * Deletes the segment.
   */
  public void delete() {
    descriptor.delete();
  }

  @Override
  public String toString() {
    return String.format("Segment[id=%d, version=%d, index=%d, length=%d]", descriptor.id(), descriptor.version(), descriptor.index(), length());
  }

}
