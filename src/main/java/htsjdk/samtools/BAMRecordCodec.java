/*
 * The MIT License
 *
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package htsjdk.samtools;

import htsjdk.samtools.util.BinaryCodec;
import htsjdk.samtools.util.RuntimeEOFException;
import htsjdk.samtools.util.SortingCollection;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Class for translating between in-memory and disk representation of BAMRecord.
 */
public class BAMRecordCodec implements SortingCollection.Codec<SAMRecord> {
    private final SAMFileHeader header;
    private final BinaryCodec binaryCodec = new BinaryCodec();
    private final BinaryTagCodec binaryTagCodec = new BinaryTagCodec(binaryCodec);
    private final SAMRecordFactory samRecordFactory;

    public BAMRecordCodec(final SAMFileHeader header) {
        this(header, new DefaultSAMRecordFactory());
    }

    public BAMRecordCodec(final SAMFileHeader header, final SAMRecordFactory factory) {
        this.header = header;
        this.samRecordFactory = factory;
    }

    @Override
    public BAMRecordCodec clone() {
        // Do not clone the references to codecs, as they must be distinct for each instance.
        return new BAMRecordCodec(this.header, this.samRecordFactory);
    }


    /** Sets the output stream that records will be written to. */
    @Override
    public void setOutputStream(final OutputStream os) {
        this.binaryCodec.setOutputStream(os);
    }

    /** Sets the output stream that records will be written to. */
    public void setOutputStream(final OutputStream os, final String filename) {
        this.binaryCodec.setOutputStream(os);
        this.binaryCodec.setOutputFileName(filename);
    }

    /** Sets the input stream that records will be read from. */
    @Override
    public void setInputStream(final InputStream is) {
        this.binaryCodec.setInputStream(is);
    }

    /** Sets the input stream that records will be read from. */
    public void setInputStream(final InputStream is, final String filename) {
        this.binaryCodec.setInputStream(is);
        this.binaryCodec.setInputFileName(filename);
    }

    /**
     * Write object to OutputStream.
     * Reference and mate reference indices must be resolvable, which either means that these have been set into the
     * SAMRecord directly, or the SAMRecord must have a header assigned into it so that reference names can be
     * resolved into indices.
     *
     * @param alignment Record to be written.
     */
    @Override
    public void encode(final SAMRecord alignment) {
        // Compute block size, as it is the first element of the file representation of SAMRecord
        final int readLength = alignment.getReadLength();

        final int cigarLength = alignment.getCigarLength();

        int blockSize = BAMFileConstants.FIXED_BLOCK_SIZE + alignment.getReadNameLength() + 1  + // null terminated
                        cigarLength * 4 +
                        (readLength + 1) / 2 + // 2 bases per byte, round up
                        readLength;

        final int attributesSize = alignment.getAttributesBinarySize();
        if (attributesSize != -1) {
            // binary attribute size already known, don't need to compute.
            blockSize += attributesSize;
        } else {
            SAMBinaryTagAndValue attribute = alignment.getBinaryAttributes();
            while (attribute != null) {
                blockSize += (BinaryTagCodec.getTagSize(attribute.value));
                attribute = attribute.getNext();
            }
        }
        if (cigarLength > 0xffff) {
            blockSize += 16; // 16: "CGBI" + realCigarLen + fakeCigar (2 operators)
        }

        int indexBin = 0;
        if (alignment.getReferenceIndex() >= 0) {
            if (alignment.getIndexingBin() != null) {
                indexBin = alignment.getIndexingBin();
            } else {
                indexBin = alignment.computeIndexingBin();
            }
        }

        // Blurt out the elements
        this.binaryCodec.writeInt(blockSize);
        this.binaryCodec.writeInt(alignment.getReferenceIndex());
        // 0-based!!
        this.binaryCodec.writeInt(alignment.getAlignmentStart() - 1);
        this.binaryCodec.writeUByte((short)(alignment.getReadNameLength() + 1));
        this.binaryCodec.writeUByte((short)alignment.getMappingQuality());
        this.binaryCodec.writeUShort(indexBin);
        if (cigarLength > 0xffff) {
            this.binaryCodec.writeUShort(2);
        } else {
            this.binaryCodec.writeUShort(cigarLength);
        }
        this.binaryCodec.writeUShort(alignment.getFlags());
        this.binaryCodec.writeInt(alignment.getReadLength());
        this.binaryCodec.writeInt(alignment.getMateReferenceIndex());
        this.binaryCodec.writeInt(alignment.getMateAlignmentStart() - 1);
        this.binaryCodec.writeInt(alignment.getInferredInsertSize());
        final byte[] variableLengthBinaryBlock = alignment.getVariableBinaryRepresentation();
        if (variableLengthBinaryBlock != null) {
            if (cigarLength <= 0xffff) {
                // Don't need to encode variable-length block, because it is unchanged from
                // when the record was read from a BAM file.
                this.binaryCodec.writeBytes(variableLengthBinaryBlock);
            } else {
                final int refAlignmentLength = alignment.getAlignmentEnd() - alignment.getAlignmentStart() + 1;
                final int cigarOffset = alignment.getReadNameLength() + 1;
                final int seqOffset = cigarOffset + cigarLength * 4;
                this.binaryCodec.writeBytes(variableLengthBinaryBlock, 0, alignment.getReadNameLength() + 1); // write QNAME; +1 for the trailing NULL
                if (readLength >= 1<<28 || refAlignmentLength >= 1<<28) { // Can't be encoded with one full clipping CIGAR
                    throw new RuntimeException("Read length or reference length in the alignment is longer than " + ((1<<28) - 1) + " and can't be encoded");
                }
                this.binaryCodec.writeInt(readLength << 4 | 4); // write the fake CIGAR: <readLength>S
                this.binaryCodec.writeInt(refAlignmentLength << 4 | 3); // write the fake CIGAR: <refLength>N
                this.binaryCodec.writeBytes(variableLengthBinaryBlock, seqOffset, variableLengthBinaryBlock.length - seqOffset); // write the rest
                this.binaryCodec.writeString("CGBI", false, false); // write the CG tag and its type
                this.binaryCodec.writeInt(cigarLength); // write the array length
                this.binaryCodec.writeBytes(variableLengthBinaryBlock, cigarOffset, cigarLength * 4); // write the CIGAR array
            }
        } else {
            if (alignment.getReadLength() != alignment.getBaseQualities().length &&
                alignment.getBaseQualities().length != 0) {
                throw new RuntimeException("Mismatch between read length and quals length writing read " +
                alignment.getReadName() + "; read length: " + alignment.getReadLength() +
                "; quals length: " + alignment.getBaseQualities().length);
            }
            this.binaryCodec.writeString(alignment.getReadName(), false, true);
            if (cigarLength <= 0xffff) {
                final int[] binaryCigar = BinaryCigarCodec.encode(alignment.getCigar());
                for (final int cigarElement : binaryCigar) {
                    // Assumption that this will fit into an integer, despite the fact
                    // that it is specced as a uint.
                    this.binaryCodec.writeInt(cigarElement);
                }
            } else { // then write a fake CIGAR: <readLength>S
                final int refAlignmentLength = alignment.getAlignmentEnd() - alignment.getAlignmentStart() + 1;
                if (readLength >= 1<<28 || refAlignmentLength >= 1<<28) // Can't be encoded with one full clipping CIGAR
                    throw new RuntimeException("Read length or reference length in the alignment is longer than " + ((1<<28) - 1));
                this.binaryCodec.writeInt(readLength << 4 | 4);
                this.binaryCodec.writeInt(refAlignmentLength << 4 | 3);
            }
            try {
                this.binaryCodec.writeBytes(SAMUtils.bytesToCompressedBases(alignment.getReadBases()));
            } catch ( final IllegalArgumentException ex ) {
                final String msg = ex.getMessage() + " in read: " +  alignment.getReadName();
                throw new IllegalStateException(msg, ex);
            }
            byte[] qualities = alignment.getBaseQualities();
            if (qualities.length == 0) {
                qualities = new byte[alignment.getReadLength()];
                Arrays.fill(qualities, (byte) 0xFF);
            }
            this.binaryCodec.writeBytes(qualities);
            SAMBinaryTagAndValue attribute = alignment.getBinaryAttributes();
            while (attribute != null) {
                this.binaryTagCodec.writeTag(attribute.tag, attribute.value, attribute.isUnsignedArray());
                attribute = attribute.getNext();
            }
            if (cigarLength > 0xffff) {
                this.binaryCodec.writeString("CGBI", false, false);
                this.binaryCodec.writeInt(cigarLength);
                final int[] binaryCigar = BinaryCigarCodec.encode(alignment.getCigar());
                for (final int cigarElement : binaryCigar) {
                    this.binaryCodec.writeInt(cigarElement);
                }
            }
        }
    }

    /**
     * Read the next record from the input stream and convert into a java object.
     *
     * @return null if no more records.  Should throw exception if EOF is encountered in the middle of
     *         a record.
     */
    @Override
    public SAMRecord decode() {
        int recordLength = 0;
        try {
            recordLength = this.binaryCodec.readInt();
        }
        catch (RuntimeEOFException e) {
            return null;
        }

        if (recordLength < BAMFileConstants.FIXED_BLOCK_SIZE) {
            throw new SAMFormatException("Invalid record length: " + recordLength);
        }
        
        final int referenceID = this.binaryCodec.readInt();
        final int coordinate = this.binaryCodec.readInt() + 1;
        final short readNameLength = this.binaryCodec.readUByte();
        final short mappingQuality = this.binaryCodec.readUByte();
        final int bin = this.binaryCodec.readUShort();
        final int cigarLen = this.binaryCodec.readUShort();
        final int flags = this.binaryCodec.readUShort();
        final int readLen = this.binaryCodec.readInt();
        final int mateReferenceID = this.binaryCodec.readInt();
        final int mateCoordinate = this.binaryCodec.readInt() + 1;
        final int insertSize = this.binaryCodec.readInt();
        final byte[] restOfRecord = new byte[recordLength - BAMFileConstants.FIXED_BLOCK_SIZE];
        this.binaryCodec.readBytes(restOfRecord);
        final BAMRecord ret = this.samRecordFactory.createBAMRecord(
                header, referenceID, coordinate, readNameLength, mappingQuality,
                bin, cigarLen, flags, readLen, mateReferenceID, mateCoordinate, insertSize, restOfRecord);

        if (null != header) {
            // don't reset a null header as this will clobber the reference and mate reference indices
            ret.setHeader(header);
        }
        return ret;
    }
}
