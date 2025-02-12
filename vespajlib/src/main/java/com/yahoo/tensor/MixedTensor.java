// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package com.yahoo.tensor;

import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A mixed tensor type. This is class is currently suitable for serialization
 * and deserialization, not yet for computation.
 *
 * A mixed tensor has a combination of mapped and indexed dimensions. By
 * reordering the mapped dimensions before the indexed dimensions, one can
 * think of mixed tensors as the mapped dimensions mapping to a
 * dense tensor. This dense tensor is called a dense subspace.
 *
 * @author lesters
 */
public class MixedTensor implements Tensor {

    /** The dimension specification for this tensor */
    private final TensorType type;
    private final int denseSubspaceSize;

    // XXX consider using "record" instead
    /** only exposed for internal use; subject to change without notice */
    public static final class DenseSubspace {
        public final TensorAddress sparseAddress;
        public final double[] cells;
        DenseSubspace(TensorAddress sparseAddress, double[] cells) {
            this.sparseAddress = sparseAddress;
            this.cells = cells;
        }
        @Override public int hashCode() {
            return Objects.hash(sparseAddress, cells[0]);
        }
        @Override public boolean equals(Object other) {
            if (other instanceof DenseSubspace o) {
                return sparseAddress.equals(o.sparseAddress) && Arrays.equals(cells, o.cells);
            }
            return false;
        }
    }

    /** The cells in the tensor */
    private final List<DenseSubspace> denseSubspaces;

    /** only exposed for internal use; subject to change without notice */
    public List<DenseSubspace> getInternalDenseSubspaces() { return denseSubspaces; }

    /** An index structure over the cell list */
    private final Index index;

    private MixedTensor(TensorType type, List<DenseSubspace> denseSubspaces, Index index) {
        this.type = type;
        this.denseSubspaceSize = index.denseSubspaceSize();
        this.denseSubspaces = List.copyOf(denseSubspaces);
        this.index = index;
        if (this.denseSubspaceSize < 1) {
            throw new IllegalStateException("invalid dense subspace size: " + denseSubspaceSize);
        }
        long count = 0;
        for (var block : this.denseSubspaces) {
            if (index.sparseMap.get(block.sparseAddress) != count) {
                throw new IllegalStateException("map vs list mismatch: block #"
                                                + count
                                                + " address maps to #"
                                                + index.sparseMap.get(block.sparseAddress));
            }
            if (block.cells.length != denseSubspaceSize) {
                throw new IllegalStateException("dense subspace size mismatch, expected "
                                                + denseSubspaceSize
                                                + " cells, but got: "
                                                + block.cells.length);
            }
            ++count;
        }
        if (count != index.sparseMap.size()) {
            throw new IllegalStateException("mismatch: list size is "
                                            + count
                                            + " but map size is "
                                            + index.sparseMap.size());
        }
    }

    /** Returns the tensor type */
    @Override
    public TensorType type() { return type; }

    /** Returns the size of the tensor measured in number of cells */
    @Override
    public long size() { return denseSubspaces.size() * denseSubspaceSize; }

    /** Returns the value at the given address */
    @Override
    public double get(TensorAddress address) {
        int blockNum = index.blockIndexOf(address);
        if (blockNum < 0 || blockNum > denseSubspaces.size()) {
            return 0.0;
        }
        int denseOffset = index.denseOffsetOf(address);
        var block = denseSubspaces.get(blockNum);
        if (denseOffset < 0 || denseOffset >= block.cells.length) {
            return 0.0;
        }
        return block.cells[denseOffset];
    }

    @Override
    public boolean has(TensorAddress address) {
        int blockNum = index.blockIndexOf(address);
        if (blockNum < 0 || blockNum > denseSubspaces.size()) {
            return false;
        }
        int denseOffset = index.denseOffsetOf(address);
        var block = denseSubspaces.get(blockNum);
        return (denseOffset >= 0 && denseOffset < block.cells.length);
    }

    /**
     * Returns an iterator over the cells of this tensor.
     * Cells are returned in order of increasing indexes in the
     * indexed dimensions, increasing indexes of later dimensions
     * in the dimension type before earlier. No guarantee is
     * given for the order of sparse dimensions.
     */
    @Override
    public Iterator<Cell> cellIterator() {
        return new Iterator<>() {
            final Iterator<DenseSubspace> blockIterator = denseSubspaces.iterator();
            DenseSubspace currBlock = null;
            int currOffset = denseSubspaceSize;
            @Override
            public boolean hasNext() {
                return (currOffset < denseSubspaceSize || blockIterator.hasNext());
            }
            @Override
            public Cell next() {
                if (currOffset == denseSubspaceSize) {
                    currBlock = blockIterator.next();
                    currOffset = 0;
                }
                TensorAddress fullAddr = index.fullAddressOf(currBlock.sparseAddress, currOffset);
                double value = currBlock.cells[currOffset++];
                return new Cell(fullAddr, value);
            }
        };
    }

    /**
     * Returns an iterator over the values of this tensor.
     * The iteration order is the same as for cellIterator.
     */
    @Override
    public Iterator<Double> valueIterator() {
        return new Iterator<>() {
            final Iterator<DenseSubspace> blockIterator = denseSubspaces.iterator();
            double[] currBlock = null;
            int currOffset = denseSubspaceSize;
            @Override
            public boolean hasNext() {
                return (currOffset < denseSubspaceSize || blockIterator.hasNext());
            }
            @Override
            public Double next() {
                if (currOffset == denseSubspaceSize) {
                    currBlock = blockIterator.next().cells;
                    currOffset = 0;
                }
                return currBlock[currOffset++];
            }
        };
    }

    @Override
    public Map<TensorAddress, Double> cells() {
        ImmutableMap.Builder<TensorAddress, Double> builder = new ImmutableMap.Builder<>();
        var iter = cellIterator();
        while (iter.hasNext()) {
            Cell cell = iter.next();
            builder.put(cell.getKey(), cell.getValue());
        }
        return builder.build();
    }

    @Override
    public Tensor withType(TensorType other) {
        if (!this.type.isRenamableTo(type)) {
            throw new IllegalArgumentException("MixedTensor.withType: types are not compatible. Current type: '" +
                                               this.type + "', requested type: '" + type + "'");
        }
        return new MixedTensor(other, denseSubspaces, index);
    }

    @Override
    public Tensor remove(Set<TensorAddress> addresses) {
        var indexBuilder = new Index.Builder(type);
        List<DenseSubspace> list = new ArrayList<>();
        for (var block : denseSubspaces) {
            if ( ! addresses.contains(block.sparseAddress)) {  // assumption: addresses only contain the sparse part
                indexBuilder.addBlock(block.sparseAddress, list.size());
                list.add(block);
            }
        }
        return new MixedTensor(type, list, indexBuilder.build());
    }

    @Override
    public int hashCode() { return Objects.hash(type, denseSubspaces); }

    @Override
    public String toString() {
        return toString(true, true);
    }

    @Override
    public String toString(boolean withType, boolean shortForms) {
        return toString(withType, shortForms, Long.MAX_VALUE);
    }

    @Override
    public String toAbbreviatedString(boolean withType, boolean shortForms) {
        return toString(withType, shortForms, Math.max(2, 10 / (type().dimensions().stream().filter(TensorType.Dimension::isMapped).count() + 1)));
    }

    private String toString(boolean withType, boolean shortForms, long maxCells) {
        if (! shortForms
            || type.rank() == 0
            || type.rank() > 1 && type.dimensions().stream().filter(TensorType.Dimension::isIndexed).anyMatch(d -> d.size().isEmpty())
            || type.dimensions().stream().filter(TensorType.Dimension::isMapped).count() > 1)
            return Tensor.toStandardString(this, withType, shortForms, maxCells);

        return (withType ? type + ":" : "") + index.contentToString(this, maxCells);
    }

    @Override
    public boolean equals(Object other) {
        if ( ! ( other instanceof Tensor)) return false;
        return Tensor.equals(this, ((Tensor)other));
    }

    /** Returns the size of dense subspaces */
    public long denseSubspaceSize() {
        return denseSubspaceSize;
    }

    /**
     * Base class for building mixed tensors.
     */
    public abstract static class Builder implements Tensor.Builder {

        final TensorType type;

        /**
         * Create a builder depending upon the type of indexed dimensions.
         * If at least one indexed dimension is unbound, we create
         * a temporary structure while finding dimension bounds.
         */
        public static Builder of(TensorType type) {
            if (type.dimensions().stream().anyMatch(d -> d instanceof TensorType.IndexedUnboundDimension)) {
                return new UnboundBuilder(type);
            } else {
                return new BoundBuilder(type);
            }
        }

        private Builder(TensorType type) {
            this.type = type;
        }

        @Override
        public TensorType type() {
            return type;
        }

        @Override
        public Tensor.Builder cell(float value, long... labels) {
            return cell((double)value, labels);
        }

        @Override
        public Tensor.Builder cell(double value, long... labels) {
            throw new UnsupportedOperationException("Not implemented.");
        }

        @Override
        public CellBuilder cell() {
            return new CellBuilder(type(), this);
        }

        @Override
        public abstract MixedTensor build();
    }

    /**
     * Builder for mixed tensors with bound indexed dimensions.
     */
    public static class BoundBuilder extends Builder {

        /** For each sparse partial address, hold a dense subspace */
        private final Map<TensorAddress, double[]> denseSubspaceMap = new HashMap<>();
        private final Index.Builder indexBuilder;
        private final Index index;
        private final TensorType denseSubtype;

        private BoundBuilder(TensorType type) {
            super(type);
            indexBuilder = new Index.Builder(type);
            index = indexBuilder.index();
            denseSubtype = new TensorType(type.valueType(),
                                          type.dimensions().stream().filter(TensorType.Dimension::isIndexed).toList());
        }

        public long denseSubspaceSize() {
            return index.denseSubspaceSize();
        }

        private double[] denseSubspace(TensorAddress sparseAddress) {
            if (!denseSubspaceMap.containsKey(sparseAddress)) {
                denseSubspaceMap.put(sparseAddress, new double[(int)denseSubspaceSize()]);
            }
            return denseSubspaceMap.get(sparseAddress);
        }

        public IndexedTensor.DirectIndexBuilder denseSubspaceBuilder(TensorAddress sparseAddress) {
            double[] values = new double[(int)denseSubspaceSize()];
            denseSubspaceMap.put(sparseAddress, values);
            return new DenseSubspaceBuilder(denseSubtype, values);
        }

        @Override
        public Tensor.Builder cell(TensorAddress address, float value) {
            return cell(address, (double)value);
        }

        @Override
        public Tensor.Builder cell(TensorAddress address, double value) {
            TensorAddress sparsePart = index.sparsePartialAddress(address);
            int denseOffset = index.denseOffsetOf(address);
            double[] denseSubspace = denseSubspace(sparsePart);
            denseSubspace[denseOffset] = value;
            return this;
        }

        public Tensor.Builder block(TensorAddress sparsePart, double[] values) {
            int denseSubspaceSize = (int)denseSubspaceSize();
            if (values.length < denseSubspaceSize)
                throw new IllegalArgumentException("Block should have " + denseSubspaceSize +
                                                   " values, but has only " + values.length);
            double[] denseSubspace = denseSubspace(sparsePart);
            System.arraycopy(values, 0, denseSubspace, 0, denseSubspaceSize);
            return this;
        }

        @Override
        public MixedTensor build() {
            List<DenseSubspace> list = new ArrayList<>();
            for (Map.Entry<TensorAddress, double[]> entry : denseSubspaceMap.entrySet()) {
                TensorAddress sparsePart = entry.getKey();
                double[] denseSubspace = entry.getValue();
                var block = new DenseSubspace(sparsePart, denseSubspace);
                indexBuilder.addBlock(sparsePart, list.size());
                list.add(block);
            }
            return new MixedTensor(type, list, indexBuilder.build());
        }

        public static BoundBuilder of(TensorType type) {
            return new BoundBuilder(type);
        }
    }

    /**
     * Temporarily stores all cells to find bounds of indexed dimensions,
     * then creates a tensor using BoundBuilder. This is due to the
     * fact that for serialization the size of the dense subspace must be
     * known, and equal for all dense subspaces. A side effect is that the
     * tensor type is effectively changed, such that unbound indexed
     * dimensions become bound.
     */
    private static class UnboundBuilder extends Builder {

        private final Map<TensorAddress, Double> cells;
        private final long[] dimensionBounds;

        private UnboundBuilder(TensorType type) {
            super(type);
            cells = new HashMap<>();
            dimensionBounds = new long[type.dimensions().size()];
        }

        @Override
        public Tensor.Builder cell(TensorAddress address, float value) {
            return cell(address, (double)value);
        }

        @Override
        public Tensor.Builder cell(TensorAddress address, double value) {
            cells.put(address, value);
            trackBounds(address);
            return this;
        }

        @Override
        public MixedTensor build() {
            TensorType boundType = createBoundType();
            BoundBuilder builder = new BoundBuilder(boundType);
            for (Map.Entry<TensorAddress, Double> cell : cells.entrySet()) {
                builder.cell(cell.getKey(), cell.getValue());
            }
            return builder.build();
        }

        private void trackBounds(TensorAddress address) {
            for (int i = 0; i < type.dimensions().size(); ++i) {
                TensorType.Dimension dimension = type.dimensions().get(i);
                if (dimension.isIndexed()) {
                    dimensionBounds[i] = Math.max(address.numericLabel(i), dimensionBounds[i]);
                }
            }
        }

        private TensorType createBoundType() {
            TensorType.Builder typeBuilder = new TensorType.Builder(type().valueType());
            for (int i = 0; i < type.dimensions().size(); ++i) {
                TensorType.Dimension dimension = type.dimensions().get(i);
                if (!dimension.isIndexed()) {
                    typeBuilder.mapped(dimension.name());
                } else {
                    long size = dimension.size().orElse(dimensionBounds[i] + 1);
                    typeBuilder.indexed(dimension.name(), size);
                }
            }
            return typeBuilder.build();
        }

        public static UnboundBuilder of(TensorType type) {
            return new UnboundBuilder(type);
        }
    }

    /**
     * An immutable index into a list of cells.
     * Contains additional information required
     * for handling mixed tensor addresses.
     * Assumes indexed dimensions are bound.
     */
    private static class Index {

        private final TensorType type;
        private final TensorType sparseType;
        private final TensorType denseType;
        private final List<TensorType.Dimension> mappedDimensions;
        private final List<TensorType.Dimension> indexedDimensions;

        private ImmutableMap<TensorAddress, Integer> sparseMap;
        private final int denseSubspaceSize;

        static private int computeDSS(List<TensorType.Dimension> dimensions) {
            long denseSubspaceSize = 1;
            for (var dimension : dimensions) {
                denseSubspaceSize *= dimension.size()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown size of indexed dimension"));
            }
            return (int) denseSubspaceSize;
        }

        private Index(TensorType type) {
            this.type = type;
            this.mappedDimensions = type.dimensions().stream().filter(d -> !d.isIndexed()).toList();
            this.indexedDimensions = type.dimensions().stream().filter(TensorType.Dimension::isIndexed).toList();
            this.sparseType = createPartialType(type.valueType(), mappedDimensions);
            this.denseType = createPartialType(type.valueType(), indexedDimensions);
            this.denseSubspaceSize = computeDSS(this.indexedDimensions);
        }

        int blockIndexOf(TensorAddress address) {
            TensorAddress sparsePart = sparsePartialAddress(address);
            return sparseMap.getOrDefault(sparsePart, -1);
        }

        int denseOffsetOf(TensorAddress address) {
            long innerSize = 1;
            long offset = 0;
            for (int i = type.dimensions().size(); --i >= 0; ) {
                TensorType.Dimension dimension = type.dimensions().get(i);
                if (dimension.isIndexed()) {
                    long label = address.numericLabel(i);
                    offset += label * innerSize;
                    innerSize *= dimension.size().orElseThrow(() ->
                            new IllegalArgumentException("Unknown size of indexed dimension."));
                }
            }
            return (int) offset;
        }

        public int denseSubspaceSize() {
            return denseSubspaceSize;
        }

        private TensorAddress sparsePartialAddress(TensorAddress address) {
            if (type.dimensions().size() != address.size())
                throw new IllegalArgumentException("Tensor type of " + this + " is not the same size as " + address);
            TensorAddress.Builder builder = new TensorAddress.Builder(sparseType);
            for (int i = 0; i < type.dimensions().size(); ++i) {
                TensorType.Dimension dimension = type.dimensions().get(i);
                if ( ! dimension.isIndexed())
                    builder.add(dimension.name(), address.label(i));
            }
            return builder.build();
        }

        private TensorAddress denseOffsetToAddress(long denseOffset) {
            if (denseOffset < 0 || denseOffset > denseSubspaceSize) {
                throw new IllegalArgumentException("Offset out of bounds");
            }

            long restSize = denseOffset;
            long innerSize = denseSubspaceSize;
            long[] labels = new long[indexedDimensions.size()];

            for (int i = 0; i < labels.length; ++i) {
                TensorType.Dimension dimension = indexedDimensions.get(i);
                long dimensionSize = dimension.size().orElseThrow(() ->
                        new IllegalArgumentException("Unknown size of indexed dimension."));

                innerSize /= dimensionSize;
                labels[i] = restSize / innerSize;
                restSize %= innerSize;
            }
            return TensorAddress.of(labels);
        }

        TensorAddress fullAddressOf(TensorAddress sparsePart, long denseOffset) {
            TensorAddress densePart = denseOffsetToAddress(denseOffset);
            String[] labels = new String[type.dimensions().size()];
            int mappedIndex = 0;
            int indexedIndex = 0;
            for (TensorType.Dimension d : type.dimensions()) {
                if (d.isIndexed()) {
                    labels[mappedIndex + indexedIndex] = densePart.label(indexedIndex);
                    indexedIndex++;
                } else {
                    labels[mappedIndex + indexedIndex] = sparsePart.label(mappedIndex);
                    mappedIndex++;
                }
            }
            return TensorAddress.of(labels);
        }

        @Override
        public String toString() {
            return "index into " + type;
        }

        private String contentToString(MixedTensor tensor, long maxCells) {
            if (mappedDimensions.size() > 1) throw new IllegalStateException("Should be ensured by caller");
            if (mappedDimensions.size() == 0) {
                StringBuilder b = new StringBuilder();
                int cellsWritten = denseSubspaceToString(tensor, 0, maxCells, b);
                if (cellsWritten == maxCells && cellsWritten < tensor.size())
                    b.append("...]");
                return b.toString();
            }

            // Exactly 1 mapped dimension
            StringBuilder b = new StringBuilder("{");
            var cellEntries = new ArrayList<>(sparseMap.entrySet());
            cellEntries.sort(Map.Entry.comparingByKey());
            int cellsWritten = 0;
            for (int index = 0; index < cellEntries.size() && cellsWritten < maxCells; index++) {
                if (index > 0)
                    b.append(", ");
                b.append(TensorAddress.labelToString(cellEntries.get(index).getKey().label(0)));
                b.append(":");
                cellsWritten += denseSubspaceToString(tensor, cellEntries.get(index).getValue(), maxCells - cellsWritten, b);
            }
            if (cellsWritten >= maxCells && cellsWritten < tensor.size())
                b.append(", ...");
            b.append("}");
            return b.toString();
        }

        private int denseSubspaceToString(MixedTensor tensor, int subspaceIndex, long maxCells, StringBuilder b) {
            if (maxCells <= 0) {
                return 0;
            }
            if (denseSubspaceSize == 1) {
                b.append(getDouble(subspaceIndex, 0, tensor));
                return 1;
            }
            IndexedTensor.Indexes indexes = IndexedTensor.Indexes.of(denseType);
            int index = 0;
            for (; index < denseSubspaceSize && index < maxCells; index++) {
                indexes.next();
                if (index > 0)
                    b.append(", ");

                // start brackets
                for (int i = 0; i < indexes.nextDimensionsAtStart(); i++)
                    b.append("[");

                // value
                switch (type.valueType()) {
                    case DOUBLE:   b.append(getDouble(subspaceIndex, index, tensor)); break;
                    case FLOAT:    b.append(getDouble(subspaceIndex, index, tensor)); break; // TODO: Really use floats
                    case BFLOAT16: b.append(getDouble(subspaceIndex, index, tensor)); break;
                    case INT8:     b.append(getDouble(subspaceIndex, index, tensor)); break;
                    default:
                        throw new IllegalStateException("Unexpected value type " + type.valueType());
                }

                // end bracket
                for (int i = 0; i < indexes.nextDimensionsAtEnd(); i++)
                    b.append("]");
            }
            return index;
        }

        private double getDouble(int subspaceIndex, int denseOffset, MixedTensor tensor) {
            return tensor.denseSubspaces.get(subspaceIndex).cells[denseOffset];
        }

        static class Builder {

            private final Index index;
            private final ImmutableMap.Builder<TensorAddress, Integer> builder;

            Builder(TensorType type) {
                index = new Index(type);
                builder = new ImmutableMap.Builder<>();
            }

            void addBlock(TensorAddress address, int sz) {
                builder.put(address, sz);
            }

            Index build() {
                index.sparseMap = builder.build();
                return index;
            }

            Index index() {
                return index;
            }
        }
    }

    private static class DenseSubspaceBuilder implements IndexedTensor.DirectIndexBuilder {

        private final TensorType type;
        private final double[] values;

        public DenseSubspaceBuilder(TensorType type, double[] values) {
            this.type = type;
            this.values = values;
        }

        @Override
        public TensorType type() { return type; }

        @Override
        public void cellByDirectIndex(long index, double value) {
            values[(int)index] = value;
        }

        @Override
        public void cellByDirectIndex(long index, float value) {
            values[(int)index] = value;
        }

    }

    public static TensorType createPartialType(TensorType.Value valueType, List<TensorType.Dimension> dimensions) {
        TensorType.Builder builder = new TensorType.Builder(valueType);
        for (TensorType.Dimension dimension : dimensions) {
            builder.set(dimension);
        }
        return builder.build();
    }

}
