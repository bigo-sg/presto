/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner;

import com.facebook.presto.metadata.FunctionManager;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.operator.aggregation.MaxDataSizeForStats;
import com.facebook.presto.operator.aggregation.SumDataSizeForStats;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.spi.statistics.ColumnStatisticMetadata;
import com.facebook.presto.spi.statistics.ColumnStatisticType;
import com.facebook.presto.spi.statistics.TableStatisticType;
import com.facebook.presto.spi.statistics.TableStatisticsMetadata;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.analyzer.TypeSignatureProvider;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.StatisticAggregations;
import com.facebook.presto.sql.planner.plan.StatisticAggregationsDescriptor;
import com.facebook.presto.sql.tree.SymbolReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.spi.statistics.TableStatisticType.ROW_COUNT;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.Objects.requireNonNull;

public class StatisticsAggregationPlanner
{
    private final SymbolAllocator symbolAllocator;
    private final Metadata metadata;

    public StatisticsAggregationPlanner(SymbolAllocator symbolAllocator, Metadata metadata)
    {
        this.symbolAllocator = requireNonNull(symbolAllocator, "symbolAllocator is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    public TableStatisticAggregation createStatisticsAggregation(TableStatisticsMetadata statisticsMetadata, Map<String, VariableReferenceExpression> columnToVariableMap)
    {
        StatisticAggregationsDescriptor.Builder<VariableReferenceExpression> descriptor = StatisticAggregationsDescriptor.builder();

        List<String> groupingColumns = statisticsMetadata.getGroupingColumns();
        List<VariableReferenceExpression> groupingVariables = groupingColumns.stream()
                .map(columnToVariableMap::get)
                .collect(toImmutableList());

        for (int i = 0; i < groupingVariables.size(); i++) {
            descriptor.addGrouping(groupingColumns.get(i), groupingVariables.get(i));
        }

        ImmutableMap.Builder<VariableReferenceExpression, AggregationNode.Aggregation> aggregations = ImmutableMap.builder();
        FunctionManager functionManager = metadata.getFunctionManager();
        for (TableStatisticType type : statisticsMetadata.getTableStatistics()) {
            if (type != ROW_COUNT) {
                throw new PrestoException(NOT_SUPPORTED, "Table-wide statistic type not supported: " + type);
            }
            String count = "count";
            AggregationNode.Aggregation aggregation = new AggregationNode.Aggregation(
                    functionManager.lookupFunction(count, ImmutableList.of()),
                    ImmutableList.of(),
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    Optional.empty());
            VariableReferenceExpression variable = symbolAllocator.newVariable("rowCount", BIGINT);
            aggregations.put(variable, aggregation);
            descriptor.addTableStatistic(ROW_COUNT, variable);
        }

        for (ColumnStatisticMetadata columnStatisticMetadata : statisticsMetadata.getColumnStatistics()) {
            String columnName = columnStatisticMetadata.getColumnName();
            ColumnStatisticType statisticType = columnStatisticMetadata.getStatisticType();
            VariableReferenceExpression inputVariable = columnToVariableMap.get(columnName);
            verify(inputVariable != null, "inputVariable is null");
            ColumnStatisticsAggregation aggregation = createColumnAggregation(statisticType, inputVariable);
            VariableReferenceExpression variable = symbolAllocator.newVariable(statisticType + ":" + columnName, aggregation.getOutputType());
            aggregations.put(variable, aggregation.getAggregation());
            descriptor.addColumnStatistic(columnStatisticMetadata, variable);
        }

        StatisticAggregations aggregation = new StatisticAggregations(aggregations.build(), groupingVariables);
        return new TableStatisticAggregation(aggregation, descriptor.build());
    }

    private ColumnStatisticsAggregation createColumnAggregation(ColumnStatisticType statisticType, VariableReferenceExpression input)
    {
        SymbolReference symbolReference = new SymbolReference(input.getName());
        switch (statisticType) {
            case MIN_VALUE:
                return createAggregation("min", symbolReference, input.getType(), input.getType());
            case MAX_VALUE:
                return createAggregation("max", symbolReference, input.getType(), input.getType());
            case NUMBER_OF_DISTINCT_VALUES:
                return createAggregation("approx_distinct", symbolReference, input.getType(), BIGINT);
            case NUMBER_OF_NON_NULL_VALUES:
                return createAggregation("count", symbolReference, input.getType(), BIGINT);
            case NUMBER_OF_TRUE_VALUES:
                return createAggregation("count_if", symbolReference, BOOLEAN, BIGINT);
            case TOTAL_SIZE_IN_BYTES:
                return createAggregation(SumDataSizeForStats.NAME, symbolReference, input.getType(), BIGINT);
            case MAX_VALUE_SIZE_IN_BYTES:
                return createAggregation(MaxDataSizeForStats.NAME, symbolReference, input.getType(), BIGINT);
            default:
                throw new IllegalArgumentException("Unsupported statistic type: " + statisticType);
        }
    }

    private ColumnStatisticsAggregation createAggregation(String functionName, SymbolReference input, Type inputType, Type outputType)
    {
        FunctionManager functionManager = metadata.getFunctionManager();
        FunctionHandle functionHandle = functionManager.lookupFunction(functionName, TypeSignatureProvider.fromTypes(ImmutableList.of(inputType)));
        Type resolvedType = metadata.getType(getOnlyElement(functionManager.getFunctionMetadata(functionHandle).getArgumentTypes()));
        verify(resolvedType.equals(inputType), "resolved function input type does not match the input type: %s != %s", resolvedType, inputType);
        return new ColumnStatisticsAggregation(
                new AggregationNode.Aggregation(
                        functionHandle,
                        ImmutableList.of(input),
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        Optional.empty()),
                outputType);
    }

    public static class TableStatisticAggregation
    {
        private final StatisticAggregations aggregations;
        private final StatisticAggregationsDescriptor<VariableReferenceExpression> descriptor;

        private TableStatisticAggregation(
                StatisticAggregations aggregations,
                StatisticAggregationsDescriptor<VariableReferenceExpression> descriptor)
        {
            this.aggregations = requireNonNull(aggregations, "statisticAggregations is null");
            this.descriptor = requireNonNull(descriptor, "descriptor is null");
        }

        public StatisticAggregations getAggregations()
        {
            return aggregations;
        }

        public StatisticAggregationsDescriptor<VariableReferenceExpression> getDescriptor()
        {
            return descriptor;
        }
    }

    public static class ColumnStatisticsAggregation
    {
        private final AggregationNode.Aggregation aggregation;
        private final Type outputType;

        private ColumnStatisticsAggregation(AggregationNode.Aggregation aggregation, Type outputType)
        {
            this.aggregation = requireNonNull(aggregation, "aggregation is null");
            this.outputType = requireNonNull(outputType, "outputType is null");
        }

        public AggregationNode.Aggregation getAggregation()
        {
            return aggregation;
        }

        public Type getOutputType()
        {
            return outputType;
        }
    }
}
