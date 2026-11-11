package org.pgledger.database.jdbc;

import java.util.Set;
import java.util.function.Function;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.data.convert.ReadingConverter;

/** Reading half of the converter pair of a value record that is stored as one text column. */
@ReadingConverter
final class StringValueReader<T> implements GenericConverter {

    private final Class<T> type;
    private final Function<String, T> factory;

    StringValueReader(Class<T> type, Function<String, T> factory) {
        this.type = type;
        this.factory = factory;
    }

    @Override
    public Set<ConvertiblePair> getConvertibleTypes() {
        return Set.of(new ConvertiblePair(String.class, type));
    }

    @Override
    public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
        return source == null ? null : factory.apply((String) source);
    }
}
