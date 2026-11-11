package org.pgledger.database.jdbc;

import java.util.Set;
import java.util.function.Function;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.data.convert.WritingConverter;

/** Writing half of the converter pair of a value record that is stored as one text column. */
@WritingConverter
final class StringValueWriter<T> implements GenericConverter {

    private final Class<T> type;
    private final Function<T, String> text;

    StringValueWriter(Class<T> type, Function<T, String> text) {
        this.type = type;
        this.text = text;
    }

    @Override
    public Set<ConvertiblePair> getConvertibleTypes() {
        return Set.of(new ConvertiblePair(type, String.class));
    }

    @Override
    public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
        return source == null ? null : text.apply(type.cast(source));
    }
}
