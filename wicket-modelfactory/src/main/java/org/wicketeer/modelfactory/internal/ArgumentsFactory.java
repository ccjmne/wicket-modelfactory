//
//
// Copyright 2012-2012 Uwe Schäfer <uwe@codesmell.de>
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package org.wicketeer.modelfactory.internal;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;

/**
 * An utility class of static factory methods that creates arguments and binds
 * them with their placeholders
 * 
 * @author Mario Fusco
 */
public final class ArgumentsFactory
{

    private ArgumentsFactory()
    {
    }

    // ////////////////////////////////////////////////////////////////////////
    // /// Factory
    // ////////////////////////////////////////////////////////////////////////

    /**
     * Constructs a proxy object that mocks the given Class registering all the
     * subsequent invocations on the object.
     * 
     * @param clazz
     *            The class of the object to be mocked
     * @return An object of the given class that register all the invocations
     *         made on it
     */
    public static <T> T createArgument(final Class<T> clazz)
    {
        return createArgument(clazz, new InvocationSequence(clazz));
    }

    @SuppressWarnings("unchecked")
    static <T> T createArgument(final Class<T> clazz, final InvocationSequence invocationSequence)
    {
        T placeholder = registerNewArgument(clazz, invocationSequence);
        return placeholder;
    }

    private static <T> T registerNewArgument(final Class<T> clazz, final InvocationSequence invocationSequence)
    {
        @SuppressWarnings("unchecked")
        T placeholder = (T) createPlaceholder(clazz, invocationSequence);
        bindArgument(placeholder, new Argument<T>(invocationSequence));
        return placeholder;
    }

    private static Object createPlaceholder(final Class<?> clazz, final InvocationSequence invocationSequence)
    {
        return !Modifier.isFinal(clazz.getModifiers()) ? ProxyUtil.createProxy(new ProxyArgument(clazz,
                invocationSequence), clazz, false) : createArgumentPlaceholder(clazz);
    }

    // ////////////////////////////////////////////////////////////////////////
    // /// Arguments
    // ////////////////////////////////////////////////////////////////////////

    private static final Map<Object, Argument<?>> ARGUMENTS_BY_PLACEHOLDER = Collections.synchronizedMap(new LRUMap(
            5000));

    private static <T> void bindArgument(final T placeholder, final Argument<T> argument)
    {

        ARGUMENTS_BY_PLACEHOLDER.put(placeholder, argument);

    }

    /**
     * Converts a placeholder with the actual argument to which is bound
     * 
     * @param placeholder
     *            The placeholder used to retrieve a registered argument
     * @return The argument bound to the given placeholder
     */
    public static <T> Argument<T> actualArgument(final T placeholder)
    {
        Argument<T> actualArgument = placeholderToArgument(placeholder);
        if (actualArgument == null)
        {
            throw new ArgumentConversionException("Unable to convert the placeholder " + placeholder
                    + " in a valid argument");
        }
        return actualArgument;
    }

    @SuppressWarnings("unchecked")
    private static <T> Argument<T> placeholderToArgument(final T placeholder)
    {
        if (placeholder instanceof Argument)
        {
            return (Argument<T>) placeholder;
        }
        return (Argument<T>) ARGUMENTS_BY_PLACEHOLDER.get(placeholder);
    }

    // ////////////////////////////////////////////////////////////////////////
    // /// Placeholders
    // ////////////////////////////////////////////////////////////////////////

    /**
     * Creates a placeholder of the given class for a non-bound closure argument
     * 
     * @param clazz
     *            The class of the placeholder
     * @return A placeholder of the given class
     */
    @SuppressWarnings("unchecked")
    public static <T> T createClosureArgumentPlaceholder(final Class<T> clazz)
    {
        if (clazz == Class.class)
        {
            return (T) ArgumentsFactory.class;
        }
        return ProxyUtil.isProxable(clazz) ? createArgument(clazz) : createFinalArgumentPlaceholder(clazz);
    }

    public static <T> Argument<T> placeholderToClosureArgument(final T placeholder)
    {
        return placeholderToArgument(placeholder);
    }

    private static final Integer FINAL_PLACEHOLDER_SEED = Integer.MIN_VALUE / 2 - 1974;

    @SuppressWarnings(
    { "unchecked", "rawtypes" })
    private static <T> T createFinalArgumentPlaceholder(final Class<T> clazz)
    {
        if ((clazz == Boolean.TYPE) || (clazz == Boolean.class))
        {
            return (T) Boolean.FALSE;
        }
        if (clazz.isEnum())
        {
            return (T) EnumSet.allOf((Class<? extends Enum>) clazz).iterator().next();
        }
        return (T) createArgumentPlaceholder(clazz, FINAL_PLACEHOLDER_SEED);
    }

    private static final AtomicInteger PLACEHOLDER_COUNTER = new AtomicInteger(Integer.MIN_VALUE);

    static Object createArgumentPlaceholder(final Class<?> clazz)
    {
        return createArgumentPlaceholder(clazz, PLACEHOLDER_COUNTER.addAndGet(1));
    }

    private static Object createArgumentPlaceholder(final Class<?> clazz, final Integer placeholderId)
    {
        if (clazz.isPrimitive() || Number.class.isAssignableFrom(clazz) || (Character.class == clazz))
        {
            return getPrimitivePlaceHolder(clazz, placeholderId);
        }

        if (clazz == String.class)
        {
            return String.valueOf(placeholderId);
        }
        if (Date.class.isAssignableFrom(clazz))
        {
            return new Date(placeholderId);
        }
        if (clazz.isArray())
        {
            return Array.newInstance(clazz.getComponentType(), 1);
        }

        try
        {
            return createArgumentPlaceholderForUnknownClass(clazz, placeholderId);
        }
        catch (Exception e)
        {
            throw new ArgumentConversionException("It is not possible to create a placeholder for class: "
                    + clazz.getName(), e);
        }
    }

    private static final Map<Class<?>, FinalClassArgumentCreator<?>> FINAL_CLASS_ARGUMENT_CREATORS = Collections
            .synchronizedMap(new HashMap<Class<?>, FinalClassArgumentCreator<?>>());

    /**
     * Register a custom argument creator factory for an unknown final class
     * 
     * @param clazz
     *            The class for which this factory should be used
     * @param creator
     *            The argument factory
     * @param <T>
     */
    public static <T> void registerFinalClassArgumentCreator(final Class<T> clazz,
            final FinalClassArgumentCreator<T> creator)
    {
        if ((clazz.getModifiers() & Modifier.FINAL) == 0)
        {
            throw new RuntimeException("A custom argument creator can be registered only for final classes");
        }
        FINAL_CLASS_ARGUMENT_CREATORS.put(clazz, creator);
    }

    public static <T> void deregisterFinalClassArgumentCreator(final Class<T> clazz)
    {
        FINAL_CLASS_ARGUMENT_CREATORS.remove(clazz);
    }
    private static final Objenesis objenesis = new ObjenesisStd(true);

    private static Object createArgumentPlaceholderForUnknownClass(final Class<?> clazz, final Integer placeholderId)
            throws IllegalAccessException, InstantiationException
    {
        FinalClassArgumentCreator<?> creator = FINAL_CLASS_ARGUMENT_CREATORS.get(clazz);
        if (creator != null)
        {
            return creator.createArgumentPlaceHolder(placeholderId);
        }

        for (@SuppressWarnings("rawtypes")
        Constructor constructor : clazz.getConstructors())
        {
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length != 1)
            {
                continue;
            }
            try
            {
                if (params[0] == String.class)
                {
                    return constructor.newInstance(String.valueOf(placeholderId));
                }
                if (isNumericClass(params[0]))
                {
                    return constructor.newInstance(placeholderId);
                }
            }
            catch (IllegalAccessException e1)
            {
            }
            catch (InvocationTargetException e2)
            {
            }
        }
        return objenesis.newInstance(clazz);
    }

    private static boolean isNumericClass(final Class<?> clazz)
    {
        return isInt(clazz) || isLong(clazz);
    }

    private static Object getPrimitivePlaceHolder(final Class<?> clazz, final Integer placeholderId)
    {
        if (isInt(clazz))
        {
            return placeholderId;
        }
        if (isLong(clazz))
        {
            return placeholderId.longValue();
        }
        if (isDouble(clazz))
        {
            return placeholderId.doubleValue();
        }
        if (isFloat(clazz))
        {
            return new Float(placeholderId % 1000000);
        }
        if (isCharacter(clazz))
        {
            return Character.forDigit(placeholderId % Character.MAX_RADIX, Character.MAX_RADIX);
        }
        if (isShort(clazz))
        {
            return placeholderId.shortValue();
        }
        return placeholderId.byteValue();
    }

    private static boolean isInt(final Class<?> clazz)
    {
        return (clazz == Integer.TYPE) || (clazz == Integer.class);
    }

    private static boolean isLong(final Class<?> clazz)
    {
        return (clazz == Long.TYPE) || (clazz == Long.class);
    }

    private static boolean isDouble(final Class<?> clazz)
    {
        return (clazz == Double.TYPE) || (clazz == Double.class);
    }

    private static boolean isFloat(final Class<?> clazz)
    {
        return (clazz == Float.TYPE) || (clazz == Float.class);
    }

    private static boolean isCharacter(final Class<?> clazz)
    {
        return (clazz == Character.TYPE) || (clazz == Character.class);
    }

    private static boolean isShort(final Class<?> clazz)
    {
        return (clazz == Short.TYPE) || (clazz == Short.class);
    }
}
