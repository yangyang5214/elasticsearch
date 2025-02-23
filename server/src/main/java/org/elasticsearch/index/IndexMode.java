/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.MetadataCreateDataStreamService;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.mapper.DataStreamTimestampFieldMapper;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.mapper.MetadataFieldMapper;
import org.elasticsearch.index.mapper.RoutingFieldMapper;
import org.elasticsearch.index.mapper.TimeSeriesIdFieldMapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;

/**
 * "Mode" that controls which behaviors and settings an index supports.
 * <p>
 * For the most part this class concentrates on validating settings and
 * mappings. Most different behavior is controlled by forcing settings
 * to be set or not set and by enabling extra fields in the mapping.
 */
public enum IndexMode {
    STANDARD("standard") {
        @Override
        void validateWithOtherSettings(Map<Setting<?>, Object> settings) {
            settingRequiresTimeSeries(settings, IndexMetadata.INDEX_ROUTING_PATH);
            settingRequiresTimeSeries(settings, IndexSettings.TIME_SERIES_START_TIME);
            settingRequiresTimeSeries(settings, IndexSettings.TIME_SERIES_END_TIME);
        }

        private void settingRequiresTimeSeries(Map<Setting<?>, Object> settings, Setting<?> setting) {
            if (false == Objects.equals(setting.getDefault(Settings.EMPTY), settings.get(setting))) {
                throw new IllegalArgumentException("[" + setting.getKey() + "] requires " + tsdbMode());
            }
        }

        @Override
        public void validateMapping(MappingLookup lookup) {};

        @Override
        public void validateAlias(@Nullable String indexRouting, @Nullable String searchRouting) {}

        @Override
        public void validateTimestampFieldMapping(boolean isDataStream, MappingLookup mappingLookup) throws IOException {
            if (isDataStream) {
                MetadataCreateDataStreamService.validateTimestampFieldMapping(mappingLookup);
            }
        }

        @Override
        public CompressedXContent getDefaultMapping() {
            return null;
        }

        @Override
        public TimestampBounds getTimestampBound(IndexScopedSettings settings) {
            return null;
        }

        @Override
        public MetadataFieldMapper buildTimeSeriesIdFieldMapper() {
            // non time-series indices must not have a TimeSeriesIdFieldMapper
            return null;
        }
    },
    TIME_SERIES("time_series") {
        @Override
        void validateWithOtherSettings(Map<Setting<?>, Object> settings) {
            if (settings.get(IndexMetadata.INDEX_ROUTING_PARTITION_SIZE_SETTING) != Integer.valueOf(1)) {
                throw new IllegalArgumentException(error(IndexMetadata.INDEX_ROUTING_PARTITION_SIZE_SETTING));
            }
            for (Setting<?> unsupported : TIME_SERIES_UNSUPPORTED) {
                if (false == Objects.equals(unsupported.getDefault(Settings.EMPTY), settings.get(unsupported))) {
                    throw new IllegalArgumentException(error(unsupported));
                }
            }
            checkSetting(settings, IndexMetadata.INDEX_ROUTING_PATH);
        }

        private void checkSetting(Map<Setting<?>, Object> settings, Setting<?> setting) {
            if (Objects.equals(setting.getDefault(Settings.EMPTY), settings.get(setting))) {
                throw new IllegalArgumentException(tsdbMode() + " requires a non-empty [" + setting.getKey() + "]");
            }
        }

        private String error(Setting<?> unsupported) {
            return tsdbMode() + " is incompatible with [" + unsupported.getKey() + "]";
        }

        @Override
        public void validateMapping(MappingLookup lookup) {
            if (((RoutingFieldMapper) lookup.getMapper(RoutingFieldMapper.NAME)).required()) {
                throw new IllegalArgumentException(routingRequiredBad());
            }
        }

        @Override
        public void validateAlias(@Nullable String indexRouting, @Nullable String searchRouting) {
            if (indexRouting != null || searchRouting != null) {
                throw new IllegalArgumentException(routingRequiredBad());
            }
        }

        @Override
        public void validateTimestampFieldMapping(boolean isDataStream, MappingLookup mappingLookup) throws IOException {
            MetadataCreateDataStreamService.validateTimestampFieldMapping(mappingLookup);
        }

        @Override
        public CompressedXContent getDefaultMapping() {
            return DEFAULT_TIME_SERIES_TIMESTAMP_MAPPING;
        }

        @Override
        public TimestampBounds getTimestampBound(IndexScopedSettings settings) {
            return new TimestampBounds(settings);
        }

        private String routingRequiredBad() {
            return "routing is forbidden on CRUD operations that target indices in " + tsdbMode();
        }

        @Override
        public MetadataFieldMapper buildTimeSeriesIdFieldMapper() {
            return TimeSeriesIdFieldMapper.INSTANCE;
        }
    };

    protected String tsdbMode() {
        return "[" + IndexSettings.MODE.getKey() + "=time_series]";
    }

    public static final CompressedXContent DEFAULT_TIME_SERIES_TIMESTAMP_MAPPING;

    static {
        try {
            DEFAULT_TIME_SERIES_TIMESTAMP_MAPPING = new CompressedXContent(
                ((builder, params) -> builder.startObject(MapperService.SINGLE_MAPPING_NAME)
                    .startObject(DataStreamTimestampFieldMapper.NAME)
                    .field("enabled", true)
                    .endObject()
                    .startObject("properties")
                    .startObject(DataStreamTimestampFieldMapper.DEFAULT_PATH)
                    .field("type", DateFieldMapper.CONTENT_TYPE)
                    .endObject()
                    .endObject()
                    .endObject())
            );
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static final List<Setting<?>> TIME_SERIES_UNSUPPORTED = List.of(
        IndexSortConfig.INDEX_SORT_FIELD_SETTING,
        IndexSortConfig.INDEX_SORT_ORDER_SETTING,
        IndexSortConfig.INDEX_SORT_MODE_SETTING,
        IndexSortConfig.INDEX_SORT_MISSING_SETTING
    );

    static final List<Setting<?>> VALIDATE_WITH_SETTINGS = List.copyOf(
        Stream.concat(
            Stream.of(
                IndexMetadata.INDEX_ROUTING_PARTITION_SIZE_SETTING,
                IndexMetadata.INDEX_ROUTING_PATH,
                IndexSettings.TIME_SERIES_START_TIME,
                IndexSettings.TIME_SERIES_END_TIME
            ),
            TIME_SERIES_UNSUPPORTED.stream()
        ).collect(toSet())
    );

    private final String name;

    IndexMode(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    abstract void validateWithOtherSettings(Map<Setting<?>, Object> settings);

    /**
     * Validate the mapping for this index.
     */
    public abstract void validateMapping(MappingLookup lookup);

    /**
     * Validate aliases targeting this index.
     */
    public abstract void validateAlias(@Nullable String indexRouting, @Nullable String searchRouting);

    /**
     * validate timestamp mapping for this index.
     */
    public abstract void validateTimestampFieldMapping(boolean isDataStream, MappingLookup mappingLookup) throws IOException;

    /**
     * Get default mapping for this index or {@code null} if there is none.
     */
    @Nullable
    public abstract CompressedXContent getDefaultMapping();

    /**
     * Get timebounds
     */
    @Nullable
    public abstract TimestampBounds getTimestampBound(IndexScopedSettings settings);

    /**
     * Return an instance of the {@link TimeSeriesIdFieldMapper} that generates
     * the _tsid field. The field mapper will be added to the list of the metadata
     * field mappers for the index.
     */
    public abstract MetadataFieldMapper buildTimeSeriesIdFieldMapper();

    public static IndexMode fromString(String value) {
        return switch (value) {
            case "standard" -> IndexMode.STANDARD;
            case "time_series" -> IndexMode.TIME_SERIES;
            default -> throw new IllegalArgumentException(
                "["
                    + value
                    + "] is an invalid index mode, valid modes are: ["
                    + Arrays.stream(IndexMode.values()).map(IndexMode::toString).collect(Collectors.joining())
                    + "]"
            );
        };
    }

    @Override
    public String toString() {
        return getName();
    }
}
