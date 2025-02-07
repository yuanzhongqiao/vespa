// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.tensor;

import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * An indexed (dense) tensor.
 * <p>
 * Some methods on indexed tensors make use of a <b>standard value order</b>: Cells are ordered by increasing
 * index where dimensions to the right are incremented before indexes to the left, where the order of dimensions are
 * alphabetical by name. In consequence, tensor value ordering is independent of the order in which dimensions are
 * specified, and the values of the right-most dimension are adjacent.
 *
 * @author bratseth
 */
public abstract class IndexedTensor implements Tensor {

    /** The prescribed and possibly abstract type this is an instance of */
    private final TensorType type;

    /** The sizes of the dimensions of this in the order of the dimensions of the type */
    private final DimensionSizes dimensionSizes;

    IndexedTensor(TensorType type, DimensionSizes dimensionSizes) {
        this.type = type;
        this.dimensionSizes = dimensionSizes;
    }

    /**
     * Returns an iterator over the cells of this in the <i>standard value order</i>.
     */
    @Override
    public Iterator<Cell> cellIterator() {
        return new CellIterator();
    }

    /** Returns an iterator over all the cells in this tensor which matches the given partial address */
    // TODO: Move up to Tensor and create a mixed tensor which can implement it (and subspace iterators) efficiently
    public SubspaceIterator cellIterator(PartialAddress partialAddress, DimensionSizes iterationSizes) {
        long[] startAddress = new long[type().dimensions().size()];
        List<Integer> iterateDimensions = new ArrayList<>();
        for (int i = 0; i < type().dimensions().size(); i++) {
            long partialAddressLabel = partialAddress.numericLabel(type.dimensions().get(i).name());
            if (partialAddressLabel >= 0) // iterate at this label
                startAddress[i] = partialAddressLabel;
            else // iterate over this dimension
                iterateDimensions.add(i);
        }
        return new SubspaceIterator(iterateDimensions, startAddress, iterationSizes);
    }

    /** Returns an iterator over the values of this returned in the <i>standard value order</i> */
    @Override
    public Iterator<Double> valueIterator() {
        return new ValueIterator();
    }

    /**
     * Returns an iterator over value iterators where the outer iterator is over each unique value of the dimensions
     * given and the inner iterator is over each unique value of the rest of the dimensions, in the
     * <i>standard value order</i>
     *
     * @param dimensions the names of the dimensions of the superspace
     * @param sizes the size of each dimension in the space we are returning values for, containing
     *              one value per dimension of this tensor (in order). Each size may be the same or smaller
     *              than the corresponding size of this tensor
     */
    public Iterator<SubspaceIterator> subspaceIterator(Set<String> dimensions, DimensionSizes sizes) {
        return new SuperspaceIterator(dimensions, sizes);
    }

    /** Returns a subspace iterator having the sizes of the dimensions of this tensor */
    public Iterator<SubspaceIterator> subspaceIterator(Set<String> dimensions) {
        return subspaceIterator(dimensions, dimensionSizes);
    }

    /**
     * Returns the value at the given indexes as a double
     *
     * @param indexes the indexes into the dimensions of this. Must be one number per dimension of this
     * @throws IllegalArgumentException if any of the indexes are out of bound or a wrong number of indexes are given
     */
    public double get(long ... indexes) {
        return get((int)toValueIndex(indexes, dimensionSizes));
    }

    /**
     * Returns the value at the given indexes as a float
     *
     * @param indexes the indexes into the dimensions of this. Must be one number per dimension of this
     * @throws IllegalArgumentException if any of the indexes are out of bound or a wrong number of indexes are given
     */
    public float getFloat(long ... indexes) {
        return getFloat((int)toValueIndex(indexes, dimensionSizes));
    }

    /** Returns the value at this address, or 0.0 if there is no value at this address */
    @Override
    public double get(TensorAddress address) {
        // optimize for fast lookup within bounds:
        try {
            return get((int)toValueIndex(address, dimensionSizes, type));
        }
        catch (IllegalArgumentException e) {
            return 0.0;
        }
    }

    @Override
    public boolean has(TensorAddress address) {
        try {
            long index = toValueIndex(address, dimensionSizes, type);
            if (index < 0) return false;
            return (index < size());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns the value at the given <i>standard value order</i> index as a double.
     *
     * @param valueIndex the direct index into the underlying data.
     * @throws IllegalArgumentException if index is out of bounds
     */
    public abstract double get(long valueIndex);

    /**
     * Returns the value at the given <i>standard value order</i> index as a float.
     *
     * @param valueIndex the direct index into the underlying data.
     * @throws IllegalArgumentException if index is out of bounds
     */
    public abstract float getFloat(long valueIndex);

    static long toValueIndex(long[] indexes, DimensionSizes sizes) {
        if (indexes.length == 1) return indexes[0]; // for speed
        if (indexes.length == 0) return 0; // for speed

        long valueIndex = 0;
        for (int i = 0; i < indexes.length; i++) {
            if (indexes[i] >= sizes.size(i))
                throw new IllegalArgumentException(Arrays.toString(indexes) + " are not within bounds");
            valueIndex += productOfDimensionsAfter(i, sizes) * indexes[i];
        }
        return valueIndex;
    }

    static long toValueIndex(TensorAddress address, DimensionSizes sizes, TensorType type) {
        if (address.isEmpty()) return 0;

        long valueIndex = 0;
        for (int i = 0; i < address.size(); i++) {
            if (address.numericLabel(i) >= sizes.size(i))
                throw new IllegalArgumentException(address + " is not within the bounds of " + type);
            valueIndex += productOfDimensionsAfter(i, sizes) * address.numericLabel(i);
        }
        return valueIndex;
    }

    private static long productOfDimensionsAfter(int afterIndex, DimensionSizes sizes) {
        long product = 1;
        for (int i = afterIndex + 1; i < sizes.dimensions(); i++)
            product *= sizes.size(i);
        return product;
    }

    void throwOnIncompatibleType(TensorType type) {
        if ( ! this.type().isRenamableTo(type))
            throw new IllegalArgumentException("Can not change type from " + this.type() + " to " + type +
                                               ": Types are not compatible");
    }

    @Override
    public TensorType type() { return type; }

    @Override
    public abstract IndexedTensor withType(TensorType type);

    public DimensionSizes dimensionSizes() { return dimensionSizes; }

    public long[] shape() {
        long[] result = new long[dimensionSizes.dimensions()];
        for (int i = 0; i < result.length; ++i) {
            result[i] = dimensionSizes.size(i);
        }
        return result;
    }

    @Override
    public Map<TensorAddress, Double> cells() {
        if (dimensionSizes.dimensions() == 0)
            return Map.of(TensorAddress.of(), get(0));

        ImmutableMap.Builder<TensorAddress, Double> builder = new ImmutableMap.Builder<>();
        Indexes indexes = Indexes.of(dimensionSizes, dimensionSizes, size());
        for (long i = 0; i < size(); i++) {
            indexes.next();
            builder.put(indexes.toAddress(), get(i));
        }
        return builder.build();
    }

    @Override
    public Tensor remove(Set<TensorAddress> addresses) {
        throw new IllegalArgumentException("Remove is not supported for indexed tensors");
    }

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
        return toString(withType, shortForms, Math.max(2, 10 / (type().dimensions().stream().filter(d -> d.isMapped()).count() + 1)));
    }

    private String toString(boolean withType, boolean shortForms, long maxCells) {
        if (! shortForms || type.rank() == 0 || type.dimensions().stream().anyMatch(d -> d.size().isEmpty()))
            return Tensor.toStandardString(this, withType, shortForms, maxCells);

        Indexes indexes = Indexes.of(dimensionSizes);
        StringBuilder b = new StringBuilder();
        if (withType)
            b.append(type).append(":");
        indexedBlockToString(this, indexes, maxCells, b);
        return b.toString();
    }

    static void indexedBlockToString(IndexedTensor tensor, Indexes indexes, long maxCells, StringBuilder b) {
        int index = 0;
        for (; index < tensor.size() && index < maxCells; index++) {
            indexes.next();
            if (index > 0)
                b.append(", ");

            // start brackets
            for (int i = 0; i < indexes.nextDimensionsAtStart(); i++)
                b.append("[");

            // value
            switch (tensor.type().valueType()) {
                case DOUBLE:   b.append(tensor.get(index)); break;
                case FLOAT:    b.append(tensor.getFloat(index)); break;
                case BFLOAT16: b.append(tensor.getFloat(index)); break;
                case INT8:     b.append((byte)tensor.getFloat(index)); break;
                default:
                    throw new IllegalStateException("Unexpected value type " + tensor.type().valueType());
            }

            // end bracket and comma
            for (int i = 0; i < indexes.nextDimensionsAtEnd(); i++)
                b.append("]");
        }
        if (index == maxCells && index < tensor.size())
            b.append(", ...]");
    }

    @Override
    public boolean equals(Object other) {
        if ( ! ( other instanceof Tensor)) return false;
        return Tensor.equals(this, ((Tensor)other));
    }

    public abstract static class Builder implements Tensor.Builder {

        final TensorType type;

        private Builder(TensorType type) {
            this.type = type;
        }

        public static Builder of(TensorType type) {
            if (type.dimensions().stream().allMatch(d -> d instanceof TensorType.IndexedBoundDimension))
                return of(type, BoundBuilder.dimensionSizesOf(type));
            else
                return new UnboundBuilder(type);
        }

        /**
         * Creates a builder initialized with the given values
         *
         * @param type the type of the tensor to build
         * @param values the initial values of the tensor. This <b>transfers ownership</b> of the value array - it
         *               must not be further mutated by the caller
         */
        public static Builder of(TensorType type, float[] values) {
            if (type.dimensions().stream().allMatch(d -> d instanceof TensorType.IndexedBoundDimension))
                return of(type, BoundBuilder.dimensionSizesOf(type), values);
            else
                return new UnboundBuilder(type);
        }

        /**
         * Creates a builder initialized with the given values
         *
         * @param type the type of the tensor to build
         * @param values the initial values of the tensor. This <b>transfers ownership</b> of the value array - it
         *               must not be further mutated by the caller
         */
        public static Builder of(TensorType type, double[] values) {
            if (type.dimensions().stream().allMatch(d -> d instanceof TensorType.IndexedBoundDimension))
                return of(type, BoundBuilder.dimensionSizesOf(type), values);
            else
                return new UnboundBuilder(type);
        }

        /**
         * Create a builder with dimension size information for this instance. Must be one size entry per dimension,
         * and, agree with the type size information when specified in the type.
         * If sizes are completely specified in the type this size information is redundant.
         */
        public static Builder of(TensorType type, DimensionSizes sizes) {
            validate(type, sizes);
            switch (type.valueType()) {
                case DOUBLE: return new IndexedDoubleTensor.BoundDoubleBuilder(type, sizes);
                case FLOAT: return new IndexedFloatTensor.BoundFloatBuilder(type, sizes);
                case BFLOAT16: return new IndexedFloatTensor.BoundFloatBuilder(type, sizes);
                case INT8: return new IndexedFloatTensor.BoundFloatBuilder(type, sizes);
                default:
                    throw new IllegalStateException("Unexpected value type " + type.valueType());
            }
        }

        /**
         * Creates a builder initialized with the given values
         *
         * @param type the type of the tensor to build
         * @param values the initial values of the tensor in the <i>standard value order</i>.
         *               This <b>transfers ownership</b> of the value array - it
         *               must not be further mutated by the caller
         */
        public static Builder of(TensorType type, DimensionSizes sizes, float[] values) {
            validate(type, sizes);
            validateSizes(sizes, values.length);
            switch (type.valueType()) {
                case DOUBLE: return new IndexedDoubleTensor.BoundDoubleBuilder(type, sizes).fill(values);
                case FLOAT: return new IndexedFloatTensor.BoundFloatBuilder(type, sizes, values);
                case BFLOAT16: return new IndexedFloatTensor.BoundFloatBuilder(type, sizes, values);
                case INT8: return new IndexedFloatTensor.BoundFloatBuilder(type, sizes, values);
                default:
                    throw new IllegalStateException("Unexpected value type " + type.valueType());
            }
        }

        /**
         * Creates a builder initialized with the given values
         *
         * @param type the type of the tensor to build
         * @param values the initial values of the tensor in the <i>standard value order</i>.
         *               This <b>transfers ownership</b> of the value array - it
         *               must not be further mutated by the caller
         */
        public static Builder of(TensorType type, DimensionSizes sizes, double[] values) {
            validate(type, sizes);
            validateSizes(sizes, values.length);
            switch (type.valueType()) {
                case DOUBLE: return new IndexedDoubleTensor.BoundDoubleBuilder(type, sizes, values);
                case FLOAT: return new IndexedFloatTensor.BoundFloatBuilder(type, sizes).fill(values);
                case BFLOAT16: return new IndexedFloatTensor.BoundFloatBuilder(type, sizes).fill(values);
                case INT8: return new IndexedFloatTensor.BoundFloatBuilder(type, sizes).fill(values);
                default:
                    throw new IllegalStateException("Unexpected value type " + type.valueType());
            }
        }

        private static void validateSizes(DimensionSizes sizes, int length) {
            if (sizes.totalSize() != length) {
                throw new IllegalArgumentException("Invalid size(" + length + ") of supplied value vector." +
                        " Type specifies that size should be " + sizes.totalSize());
            }
        }

        private static void validate(TensorType type, DimensionSizes sizes) {
            // validate
            if (sizes.dimensions() != type.dimensions().size())
                throw new IllegalArgumentException(sizes.dimensions() +
                        " is the wrong number of dimensions for " + type);
            for (int i = 0; i < sizes.dimensions(); i++ ) {
                Optional<Long> size = type.dimensions().get(i).size();
                if (size.isPresent() && size.get() < sizes.size(i))
                    throw new IllegalArgumentException("Size of dimension " + type.dimensions().get(i).name() + " is " +
                            sizes.size(i) +
                            " but cannot be larger than " + size.get() + " in " + type);
            }
        }

        public abstract Builder cell(double value, long ... indexes);
        public abstract Builder cell(float value, long ... indexes);

        @Override
        public TensorType type() { return type; }

        @Override
        public abstract IndexedTensor build();

    }

    public interface DirectIndexBuilder {

        TensorType type();

        /** Sets a value by its <i>standard value order</i> index */
        void cellByDirectIndex(long index, double value);

        /** Sets a value by its <i>standard value order</i> index */
        void cellByDirectIndex(long index, float value);

    }

    /** A bound builder can create the double array directly */
    public static abstract class BoundBuilder extends Builder implements DirectIndexBuilder {

        private final DimensionSizes sizes;

        private static DimensionSizes dimensionSizesOf(TensorType type) {
            DimensionSizes.Builder b = new DimensionSizes.Builder(type.dimensions().size());
            for (int i = 0; i < type.dimensions().size(); i++)
                b.set(i, type.dimensions().get(i).size().get());
            return b.build();
        }

        BoundBuilder(TensorType type, DimensionSizes sizes) {
            super(type);
            if ( sizes.dimensions() != type.dimensions().size())
                throw new IllegalArgumentException("Must have a dimension size entry for each dimension in " + type);
            this.sizes = sizes;
        }

        public BoundBuilder fill(float[] values) {
            long index = 0;
            for (float value : values) {
                cellByDirectIndex(index++, value);
            }
            return this;
        }

        public BoundBuilder fill(double[] values) {
            long index = 0;
            for (double value : values) {
                cellByDirectIndex(index++, value);
            }
            return this;
        }

        DimensionSizes sizes() { return sizes; }

    }

    /**
     * A builder used when we don't know the size of the dimensions up front.
     * All values is all dimensions must be specified.
     */
    private static class UnboundBuilder extends Builder {

        /** List of List or Double */
        private List<Object> firstDimension = null;

        private UnboundBuilder(TensorType type) {
            super(type);
        }

        @Override
        public IndexedTensor build() {
            if (firstDimension == null) throw new IllegalArgumentException("Tensor of type " + type() + " has no values");

            if (type.dimensions().isEmpty()) // single number
                return new IndexedDoubleTensor(type, new DimensionSizes.Builder(type.dimensions().size()).build(), new double[] {(Double) firstDimension.get(0) });

            DimensionSizes dimensionSizes = findDimensionSizes(firstDimension);
            double[] values = new double[(int)dimensionSizes.totalSize()];
            fillValues(0, 0, firstDimension, dimensionSizes, values);
            return new IndexedDoubleTensor(type, dimensionSizes, values);
        }

        private DimensionSizes findDimensionSizes(List<Object> firstDimension) {
            List<Long> dimensionSizeList = new ArrayList<>(type.dimensions().size());
            findDimensionSizes(0, dimensionSizeList, firstDimension);
            DimensionSizes.Builder b = new DimensionSizes.Builder(type.dimensions().size()); // may be longer than the list but that's correct
            for (int i = 0; i < b.dimensions(); i++) {
                if (i < dimensionSizeList.size())
                    b.set(i, dimensionSizeList.get(i));
            }
            return b.build();
        }

        @SuppressWarnings("unchecked")
        private void findDimensionSizes(int currentDimensionIndex, List<Long> dimensionSizes, List<Object> currentDimension) {
            if (currentDimensionIndex == dimensionSizes.size())
                dimensionSizes.add((long)currentDimension.size());
            else if (dimensionSizes.get(currentDimensionIndex) != currentDimension.size())
                throw new IllegalArgumentException("Missing values in dimension " +
                                                   type.dimensions().get(currentDimensionIndex) + " in " + type);

            for (Object value : currentDimension)
                if (value instanceof List)
                    findDimensionSizes(currentDimensionIndex + 1, dimensionSizes, (List<Object>)value);
        }

        @SuppressWarnings("unchecked")
        private void fillValues(int currentDimensionIndex, long offset, List<Object> currentDimension,
                                DimensionSizes sizes, double[] values) {
            if (currentDimensionIndex < sizes.dimensions() - 1) { // recurse to next dimension
                for (long i = 0; i < currentDimension.size(); i++)
                    fillValues(currentDimensionIndex + 1,
                               offset + productOfDimensionsAfter(currentDimensionIndex, sizes) * i,
                               (List<Object>) currentDimension.get((int)i), sizes, values);
            } else { // last dimension - fill values
                for (long i = 0; i < currentDimension.size(); i++) {
                    values[(int)(offset + i)] = nullAsZero((Double)currentDimension.get((int)i)); // fill missing values as zero
                }
            }
        }

        private double nullAsZero(Double value) {
            if (value == null) return 0;
            return value;
        }

        @Override
        public CellBuilder cell() {
            return new CellBuilder(type, this);
        }

        @Override
        public Builder cell(TensorAddress address, float value) {
            return cell(address, (double)value);
        }

        @Override
        public Builder cell(TensorAddress address, double value) {
            long[] indexes = new long[address.size()];
            for (int i = 0; i < address.size(); i++) {
                indexes[i] = address.numericLabel(i);
            }
            cell(value, indexes);
            return this;
        }

        @Override
        public Builder cell(float value, long... indexes) {
            return cell((double)value, indexes);
        }

        /**
         * Set a value using an index API. The number of indexes must be the same as the dimensions in the type of this.
         * Values can be written in any order but all values needed to make this dense must be provided
         * before building this.
         *
         * @return this for chaining
         */
        @SuppressWarnings("unchecked")
        @Override
        public Builder cell(double value, long... indexes) {
            if (indexes.length != type.dimensions().size())
                throw new IllegalArgumentException("Wrong number of indexes (" + indexes.length + ") for " + type);

            if (indexes.length == 0) {
                firstDimension = List.of(value);
                return this;
            }

            if (firstDimension == null)
                firstDimension = new ArrayList<>();
            List<Object> currentValues = firstDimension;
            for (int dimensionIndex = 0; dimensionIndex < indexes.length; dimensionIndex++) {
                ensureCapacity(indexes[dimensionIndex], currentValues);
                if (dimensionIndex == indexes.length - 1) { // last dimension
                    currentValues.set((int)indexes[dimensionIndex], value);
                } else {
                    if (currentValues.get((int)indexes[dimensionIndex]) == null)
                        currentValues.set((int)indexes[dimensionIndex], new ArrayList<>());
                    currentValues = (List<Object>) currentValues.get((int)indexes[dimensionIndex]);
                }
            }
            return this;
        }

        /** Fill the given list with nulls if necessary to make sure it has a (possibly null) value at the given index */
        private void ensureCapacity(long index, List<Object> list) {
            while (list.size() <= index)
                list.add(list.size(), null);
        }

    }

    private final class CellIterator implements Iterator<Cell> {

        private long count = 0;
        private final Indexes indexes = Indexes.of(dimensionSizes, dimensionSizes, size());
        private final LazyCell reusedCell = new LazyCell(indexes, Double.NaN);

        @Override
        public boolean hasNext() {
            return count < indexes.size();
        }

        @Override
        public Cell next() {
            if ( ! hasNext()) throw new NoSuchElementException("No cell at " + indexes);
            count++;
            indexes.next();
            reusedCell.value = get(indexes.toSourceValueIndex());
            return reusedCell;
        }

    }

    private final class ValueIterator implements Iterator<Double> {

        private long count = 0;

        @Override
        public boolean hasNext() {
            return count < size();
        }

        @Override
        public Double next() {
            try {
                return get(count++);
            }
            catch (IllegalArgumentException e) {
                throw new NoSuchElementException("No element at position " + count);
            }
        }

    }

    private final class SuperspaceIterator implements Iterator<SubspaceIterator> {

        private final Indexes superindexes;

        /** The indexes this should iterate over */
        private final List<Integer> subdimensionIndexes;

        /**
         * The sizes of the space we'll return values of, one value for each dimension of this tensor,
         * which may be equal to or smaller than the sizes of this tensor
         */
        private final DimensionSizes iterateSizes;

        private long count = 0;

        private SuperspaceIterator(Set<String> superdimensionNames, DimensionSizes iterateSizes) {
            this.iterateSizes = iterateSizes;

            List<Integer> superdimensionIndexes = new ArrayList<>(superdimensionNames.size()); // for outer iterator
            subdimensionIndexes = new ArrayList<>(superdimensionNames.size()); // for inner iterator (max length)
            for (int i = type.dimensions().size() - 1; i >= 0; i-- ) { // iterate inner dimensions first
                if (superdimensionNames.contains(type.dimensions().get(i).name()))
                    superdimensionIndexes.add(i);
                else
                    subdimensionIndexes.add(i);
            }

            superindexes = Indexes.of(IndexedTensor.this.dimensionSizes, iterateSizes, superdimensionIndexes);
        }

        @Override
        public boolean hasNext() {
            return count < superindexes.size();
        }

        @Override
        public SubspaceIterator next() {
            if ( ! hasNext()) throw new NoSuchElementException("No cell at " + superindexes);
            count++;
            superindexes.next();
            return new SubspaceIterator(subdimensionIndexes, superindexes.indexesCopy(), iterateSizes);
        }

    }

    /**
     * An iterator over a subspace of this tensor. This is exposed to allow clients to query the size.
     * NOTE THAT the Cell returned by next is only valid until the next() call is made.
     * This is a concession to performance due to this typically being used in inner loops.
     */
    public final class SubspaceIterator implements Iterator<Tensor.Cell> {

        /**
         * This iterator will iterate over the given dimensions, in the order given
         * (the first dimension index given is incremented to exhaustion first (i.e is etc.).
         * This may be any subset of the dimensions given by address and dimensionSizes.
         */
        private final List<Integer> iterateDimensions;
        private final long[] address;
        private final DimensionSizes iterateSizes;

        private Indexes indexes;
        private long count = 0;

        /** A lazy cell for reuse */
        private final LazyCell reusedCell;

        /**
         * Creates a new subspace iterator
         *
         * @param iterateDimensions the dimensions to iterate over, given as indexes in the dimension order of the
         *                          type of the tensor this iterates over. This iterator will iterate over these
         *                          dimensions to exhaustion in the order given (the first dimension index given is
         *                          incremented  to exhaustion first etc., while other dimensions will be held
         *                          at a constant position.
         *                          This may be any subset of the dimensions given by address and dimensionSizes.
         *                          This is treated as immutable.
         * @param address the address of the first cell of this subspace.
         */
        private SubspaceIterator(List<Integer> iterateDimensions, long[] address, DimensionSizes iterateSizes) {
            this.iterateDimensions = iterateDimensions;
            this.address = address;
            this.iterateSizes = iterateSizes;
            this.indexes = Indexes.of(IndexedTensor.this.dimensionSizes, iterateSizes, iterateDimensions, address);
            reusedCell = new LazyCell(indexes, Double.NaN);
        }

        /** Returns the total number of cells in this subspace */
        public long size() {
            return indexes.size();
        }

        /** Returns the address of the cell this currently points to (which may be an invalid position) */
        public TensorAddress address() { return indexes.toAddress(); }

        /** Rewind this iterator to the first element */
        public void reset() {
            this.count = 0;
            this.indexes = Indexes.of(IndexedTensor.this.dimensionSizes, iterateSizes, iterateDimensions, address);
        }

        @Override
        public boolean hasNext() {
            return count < indexes.size();
        }

        /** Returns the next cell, which is valid until next() is called again */
        @Override
        public Cell next() {
            if ( ! hasNext()) throw new NoSuchElementException("No cell at " + indexes);
            count++;
            indexes.next();
            reusedCell.value = get(indexes.toSourceValueIndex());
            return reusedCell;
        }

    }

    /** A Cell which does not compute its TensorAddress unless it really has to */
    private final static class LazyCell extends Tensor.Cell {

        private double value;
        private final Indexes indexes;

        private LazyCell(Indexes indexes, Double value) {
            super(null, value);
            this.indexes = indexes;
        }

        @Override
        long getDirectIndex() { return indexes.toIterationValueIndex(); }

        @Override
        public TensorAddress getKey() {
            return indexes.toAddress();
        }

        @Override
        public Double getValue() { return value; }

        @Override
        public Cell detach() {
            return new Cell(getKey(), value);
        }

    }

    /**
     * An array of indexes into this tensor which are able to find the next index in the value order.
     * next() can be called once per element in the dimensions we iterate over. It must be called once
     * before accessing the first position.
     */
    public abstract static class Indexes {

        private final DimensionSizes sourceSizes;

        private final DimensionSizes iterationSizes;

        protected final long[] indexes;

        /**
         * Create indexes from a type containing bound indexed dimensions only.
         *
         * @throws IllegalStateException if the type contains dimensions which are not bound and indexed
         */
        public static Indexes of(TensorType type) {
            return of(DimensionSizes.of(type));
        }

        public static Indexes of(TensorType type, List<String> iterateDimensionOrder) {
            return of(DimensionSizes.of(type), toIterationOrder(iterateDimensionOrder, type));
        }

        public static Indexes of(DimensionSizes sizes) {
            return of(sizes, sizes);
        }

        private static Indexes of(DimensionSizes sourceSizes, DimensionSizes iterateSizes) {
            return of(sourceSizes, iterateSizes, completeIterationOrder(iterateSizes.dimensions()));
        }

        private static Indexes of(DimensionSizes sourceSizes, DimensionSizes iterateSizes, long size) {
            return of(sourceSizes, iterateSizes, completeIterationOrder(iterateSizes.dimensions()), size);
        }

        private static Indexes of(DimensionSizes sizes, List<Integer> iterateDimensions) {
            return of(sizes, sizes, iterateDimensions);
        }

        private static Indexes of(DimensionSizes sourceSizes, DimensionSizes iterateSizes, List<Integer> iterateDimensions) {
            return of(sourceSizes, iterateSizes, iterateDimensions, computeSize(iterateSizes, iterateDimensions));
        }

        private static Indexes of(DimensionSizes sourceSizes, DimensionSizes iterateSizes, List<Integer> iterateDimensions, long size) {
            return of(sourceSizes, iterateSizes, iterateDimensions, new long[iterateSizes.dimensions()], size);
        }

        private static Indexes of(DimensionSizes sourceSizes, DimensionSizes iterateSizes, List<Integer> iterateDimensions, long[] initialIndexes) {
            return of(sourceSizes, iterateSizes, iterateDimensions, initialIndexes, computeSize(iterateSizes, iterateDimensions));
        }

        private static Indexes of(DimensionSizes sourceSizes, DimensionSizes iterateSizes, List<Integer> iterateDimensions, long[] initialIndexes, long size) {
            if (size == 0) {
                return new EmptyIndexes(sourceSizes, iterateSizes, initialIndexes); // we're told explicitly there are truly no values available
            }
            else if (size == 1) {
                return new SingleValueIndexes(sourceSizes, iterateSizes, initialIndexes); // with no (iterating) dimensions, we still return one value, not zero
            }
            else if (iterateDimensions.size() == 1) {
                if (sourceSizes.equals(iterateSizes))
                    return new EqualSizeSingleDimensionIndexes(sourceSizes, iterateDimensions.get(0), initialIndexes, size);
                else
                    return new SingleDimensionIndexes(sourceSizes, iterateSizes, iterateDimensions.get(0), initialIndexes, size); // optimization
            }
            else {
                if (sourceSizes.equals(iterateSizes))
                    return new EqualSizeMultiDimensionIndexes(sourceSizes, iterateDimensions, initialIndexes, size);
                else
                    return new MultiDimensionIndexes(sourceSizes, iterateSizes, iterateDimensions, initialIndexes, size);
            }
        }

        private static List<Integer> toIterationOrder(List<String> dimensionNames, TensorType type) {
            if (dimensionNames == null) return completeIterationOrder(type.rank());

            List<Integer> iterationDimensions = new ArrayList<>(type.rank());
            for (int i = 0; i < type.rank(); i++)
                iterationDimensions.add(type.rank() - 1 - type.indexOfDimension(dimensionNames.get(i)).get());
            return iterationDimensions;
        }

        /** Since the right dimensions binds closest, iteration order is the opposite of the tensor order */
        private static List<Integer> completeIterationOrder(int length) {
            List<Integer> iterationDimensions = new ArrayList<>(length);
            for (int i = 0; i < length; i++)
                iterationDimensions.add(length - 1 - i);
            return iterationDimensions;
        }

        private Indexes(DimensionSizes sourceSizes, DimensionSizes iterationSizes, long[] indexes) {
            this.sourceSizes = sourceSizes;
            this.iterationSizes = iterationSizes;
            this.indexes = indexes;
        }

        private static long computeSize(DimensionSizes sizes, List<Integer> iterateDimensions) {
            long size = 1;
            for (int iterateDimension : iterateDimensions)
                size *= sizes.size(iterateDimension);
            return size;
        }

        /** Returns the address of the current position of these indexes */
        public TensorAddress toAddress() {
            return TensorAddress.of(indexes);
        }

        public long[] indexesCopy() {
            return Arrays.copyOf(indexes, indexes.length);
        }

        /** Returns a copy of the indexes of this which must not be modified */
        public long[] indexesForReading() { return indexes; }

        public long toSourceValueIndex() {
            return IndexedTensor.toValueIndex(indexes, sourceSizes);
        }

        long toIterationValueIndex() { return IndexedTensor.toValueIndex(indexes, iterationSizes); }

        DimensionSizes dimensionSizes() { return iterationSizes; }

        /** Returns an immutable list containing a copy of the indexes in this */
        public List<Long> toList() {
            ArrayList<Long> list = new ArrayList<>(indexes.length);
            for(long index : indexes) { list.add(index); }
            return List.copyOf(list);
        }

        @Override
        public String toString() {
            return "indexes " + Arrays.toString(indexes);
        }

        public abstract long size();

        public abstract void next();

        /** Returns whether further values are available by calling next() */
        public abstract boolean hasNext();

        /** Returns the number of dimensions in iteration order which are currently at the start position (0) */
        abstract int nextDimensionsAtStart();

        /** Returns the number of dimensions in iteration order which are currently at their end position */
        abstract int nextDimensionsAtEnd();

    }

    private final static class EmptyIndexes extends Indexes {

        private EmptyIndexes(DimensionSizes sourceSizes, DimensionSizes iterateSizes, long[] indexes) {
            super(sourceSizes, iterateSizes, indexes);
        }

        @Override
        public long size() { return 0; }

        @Override
        public void next() {}

        @Override
        public boolean hasNext() { return false; }

        @Override
        int nextDimensionsAtStart() { return 0; }

        @Override
        int nextDimensionsAtEnd() { return 0; }

    }

    private final static class SingleValueIndexes extends Indexes {

        private boolean exhausted = false;

        private SingleValueIndexes(DimensionSizes sourceSizes, DimensionSizes iterateSizes, long[] indexes) {
            super(sourceSizes, iterateSizes, indexes);
        }

        @Override
        public long size() { return 1; }

        @Override
        public void next() { exhausted = true; }

        @Override
        public boolean hasNext() { return ! exhausted; }

        @Override
        int nextDimensionsAtStart() { return 1; }

        @Override
        int nextDimensionsAtEnd() { return 1; }

    }

    private static class MultiDimensionIndexes extends Indexes {

        private final long size;

        private final List<Integer> iterateDimensions;

        private MultiDimensionIndexes(DimensionSizes sourceSizes, DimensionSizes iterateSizes, List<Integer> iterateDimensions, long[] initialIndexes, long size) {
            super(sourceSizes, iterateSizes, initialIndexes);
            this.iterateDimensions = iterateDimensions;
            this.size = size;

            // Initialize to the (virtual) position before the first cell
            indexes[iterateDimensions.get(0)]--;
        }

        /** Returns the number of values this will iterate over - i.e the product if the iterating dimension sizes */
        @Override
        public long size() {
            return size;
        }

        /**
         * Advances this to the next cell in the standard indexed tensor cell order.
         * The first call to this will put it at the first position.
         *
         * @throws RuntimeException if this is called when hasNext returns false
         */
        @Override
        public void next() {
            int iterateDimensionsIndex = 0;
            while ( indexes[iterateDimensions.get(iterateDimensionsIndex)] + 1 == dimensionSizes().size(iterateDimensions.get(iterateDimensionsIndex))) {
                indexes[iterateDimensions.get(iterateDimensionsIndex)] = 0; // carry over
                iterateDimensionsIndex++;
            }
            indexes[iterateDimensions.get(iterateDimensionsIndex)]++;
        }

        @Override
        public boolean hasNext() {
            for (int iterateDimension : iterateDimensions) {
                if (indexes[iterateDimension] + 1 < dimensionSizes().size(iterateDimension))
                    return true; // some dimension is not at the end
            }
            return false;
        }

        @Override
        int nextDimensionsAtStart() {
            int dimension = 0;
            while (dimension < iterateDimensions.size()  && indexes[iterateDimensions.get(dimension)] == 0)
                dimension++;
            return dimension;
        }

        @Override
        int nextDimensionsAtEnd() {
            int dimension = 0;
            while (dimension < iterateDimensions.size() && indexes[iterateDimensions.get(dimension)] == dimensionSizes().size(iterateDimensions.get(dimension)) - 1)
                dimension++;
            return dimension;
        }

    }

    /** In this case we can reuse the source index computation for the iteration index */
    private final static class EqualSizeMultiDimensionIndexes extends MultiDimensionIndexes {

        private long lastComputedSourceValueIndex = -1;

        private EqualSizeMultiDimensionIndexes(DimensionSizes sizes, List<Integer> iterateDimensions, long[] initialIndexes, long size) {
            super(sizes, sizes, iterateDimensions, initialIndexes, size);
        }

        @Override
        public long toSourceValueIndex() {
            return lastComputedSourceValueIndex = super.toSourceValueIndex();
        }

        // NOTE: We assume the source index always gets computed first. Otherwise using this will produce a runtime exception
        @Override
        long toIterationValueIndex() { return lastComputedSourceValueIndex; }

    }

    /** In this case we can keep track of indexes using a step instead of using the more elaborate computation */
    private final static class SingleDimensionIndexes extends Indexes {

        private final long size;

        private final int iterateDimension;

        /** Maintain this directly as an optimization for 1-d iteration */
        private long currentSourceValueIndex, currentIterationValueIndex;

        /** The iteration step in the value index space */
        private final long sourceStep, iterationStep;

        private SingleDimensionIndexes(DimensionSizes sourceSizes, DimensionSizes iterateSizes,
                                       int iterateDimension, long[] initialIndexes, long size) {
            super(sourceSizes, iterateSizes, initialIndexes);
            this.iterateDimension = iterateDimension;
            this.size = size;
            this.sourceStep = productOfDimensionsAfter(iterateDimension, sourceSizes);
            this.iterationStep = productOfDimensionsAfter(iterateDimension, iterateSizes);

            // Initialize to the (virtual) position before the first cell
            indexes[iterateDimension]--;
            currentSourceValueIndex = IndexedTensor.toValueIndex(indexes, sourceSizes);
            currentIterationValueIndex = IndexedTensor.toValueIndex(indexes, iterateSizes);
        }

        /** Returns the number of values this will iterate over - i.e the product if the iterating dimension sizes */
        @Override
        public long size() {
            return size;
        }

        /**
         * Advances this to the next cell in the standard indexed tensor cell order.
         * The first call to this will put it at the first position.
         *
         * @throws RuntimeException if this is called when hasNext returns false
         */
        @Override
        public void next() {
            indexes[iterateDimension]++;
            currentSourceValueIndex += sourceStep;
            currentIterationValueIndex += iterationStep;
        }

        @Override
        public long toSourceValueIndex() { return currentSourceValueIndex; }

        @Override
        long toIterationValueIndex() { return currentIterationValueIndex; }

        @Override
        public boolean hasNext() {
            return indexes[iterateDimension] + 1 < size;
        }

        @Override
        int nextDimensionsAtStart() { return currentSourceValueIndex == 0 ? 1 : 0; }

        @Override
        int nextDimensionsAtEnd() { return currentSourceValueIndex == size - 1 ? 1 : 0; }

    }

    /** In this case we only need to keep track of one index */
    private final static class EqualSizeSingleDimensionIndexes extends Indexes {

        private final long size;

        private final int iterateDimension;

        /** Maintain this directly as an optimization for 1-d iteration */
        private long currentValueIndex;

        /** The iteration step in the value index space */
        private final long step;

        private EqualSizeSingleDimensionIndexes(DimensionSizes sizes,
                                                int iterateDimension, long[] initialIndexes, long size) {
            super(sizes, sizes, initialIndexes);
            this.iterateDimension = iterateDimension;
            this.size = size;
            this.step = productOfDimensionsAfter(iterateDimension, sizes);

            // Initialize to the (virtual) position before the first cell
            indexes[iterateDimension]--;
            currentValueIndex = IndexedTensor.toValueIndex(indexes, sizes);
        }

        /** Returns the number of values this will iterate over - i.e the product if the iterating dimension sizes */
        @Override
        public long size() {
            return size;
        }

        /**
         * Advances this to the next cell in the standard indexed tensor cell order.
         * The first call to this will put it at the first position.
         *
         * @throws RuntimeException if this is called when hasNext returns false
         */
        @Override
        public void next() {
            indexes[iterateDimension]++;
            currentValueIndex += step;
        }

        @Override
        public boolean hasNext() {
            return indexes[iterateDimension] + 1 < size;
        }

        @Override
        public long toSourceValueIndex() { return currentValueIndex; }

        @Override
        long toIterationValueIndex() { return currentValueIndex; }

        @Override
        int nextDimensionsAtStart() { return currentValueIndex == 0 ? 1 : 0; }

        @Override
        int nextDimensionsAtEnd() { return currentValueIndex == size - 1 ? 1 : 0; }

    }

}
