/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016. Diorite (by Bartłomiej Mazur (aka GotoFinal))
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.diorite.inject.binder;

import javax.annotation.Nullable;

import java.util.function.Supplier;

import org.diorite.inject.InjectionException;

/**
 * Provides instances of {@code T}. Typically implemented by an injector. For
 * any type {@code T} that can be injected, you can also inject
 * {@code Provider<T>}. Compared to injecting {@code T} directly, injecting
 * {@code Provider<T>} enables:
 *
 * <ul>
 * <li>retrieving multiple instances.</li>
 * <li>lazy or optional retrieval of an instance.</li>
 * <li>breaking circular dependencies.</li>
 * <li>abstracting scope so you can look up an instance in a smaller scope
 * from an instance in a containing scope.</li>
 * </ul>
 *
 * <p>For example:
 *
 * <pre>
 *   class Car {
 *     &#064;Inject Car(Provider&lt;Seat&gt; seatProvider) {
 *       Seat driver = seatProvider.get();
 *       Seat passenger = seatProvider.get();
 *       ...
 *     }
 *   }</pre>
 */
@FunctionalInterface
public interface Provider<T> extends Supplier<T>
{
    /**
     * Provides a fully-constructed and injected instance of {@code T}.
     *
     * @return fully-constructed and injected instance of {@code T}.
     */
    @Override
    @Nullable
    T get();

    /**
     * Provides a fully-constructed and injected instance of {@code T}, or default value if injection returned null value.
     *
     * @param def
     *         default value to use.
     *
     * @return fully-constructed and injected instance of {@code T}.
     */
    default T orDefault(T def)
    {
        T t = this.get();
        if (t == null)
        {
            return def;
        }
        return t;
    }

    /**
     * Provides a fully-constructed and injected instance of {@code T}, or throws error if injection returned null value.
     *
     * @return fully-constructed and injected instance of {@code T}.
     *
     * @throws InjectionException
     *         if injection returned null value.
     */
    default T getNotNull() throws InjectionException
    {
        T t = this.get();
        if (t == null)
        {
            throw new InjectionException("Injected null values, but requested non-null value");
        }
        return t;
    }
}
