package com.kestrel.billing.rules;

import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

/**
 * Creates rules by their configured name: {@code volume} is {@code VolumeRule} in this package. New
 * rules need no registration, only a class.
 */
public final class RuleFactory {

    public Rule create(String name) {
        String cls = RuleFactory.class.getPackageName() + "." + name.substring(0, 1).toUpperCase(Locale.ROOT)
                + name.substring(1) + "Rule";
        try {
            return (Rule) Class.forName(cls).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("no rule '" + name + "' (" + cls + ")", ex);
        } catch (ReflectiveOperationException | ClassCastException ex) {
            throw new IllegalStateException("cannot create rule '" + name + "'", ex instanceof InvocationTargetException ite ? ite.getCause() : ex);
        }
    }
}
