/*
 * Copyright (c) 2016-2023 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Scannable;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Emits the last value from upstream only if there were no newer values emitted
 * during the time window provided by a publisher for that particular last value.
 *
 * @param <T> the source value type
 * @param <U> the value type of the duration publisher
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxSampleTimeout<T, U> extends InternalFluxOperator<T, T> {

	final Function<? super T, ? extends Publisher<U>> throttler;


	FluxSampleTimeout(Flux<? extends T> source,
					  Function<? super T, ? extends Publisher<U>> throttler) {
		super(source);
		this.throttler = Objects.requireNonNull(throttler, "throttler");
	}


	@Override
	public int getPrefetch() {
		return Integer.MAX_VALUE;
	}

	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super T> actual) {

		SampleTimeoutMain<T, U> main = new SampleTimeoutMain<>(actual, throttler);

		actual.onSubscribe(main);

		return main;
	}

	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;
		return super.scanUnsafe(key);
	}

	static final class SampleTimeoutMain<T, U>implements InnerOperator<T, T> {

		final Function<? super T, ? extends Publisher<U>> throttler;
		final CoreSubscriber<? super T>                   actual;
		final Context                                     ctx;

		volatile Subscription s;

		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<SampleTimeoutMain, Subscription> S =
				AtomicReferenceFieldUpdater.newUpdater(SampleTimeoutMain.class,
						Subscription.class,
						"s");


		private static final SampleTimeoutOther<?, ?> TERMINATED = SampleTimeoutOther.sentinel();
		volatile SampleTimeoutOther<?, ?> other = SampleTimeoutOther.sentinel();
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<SampleTimeoutMain, SampleTimeoutOther>
				OTHER = AtomicReferenceFieldUpdater.newUpdater(SampleTimeoutMain.class,
				SampleTimeoutOther.class,
				"other");

		volatile long requested;
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<SampleTimeoutMain> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(SampleTimeoutMain.class, "requested");

		volatile Throwable error;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<SampleTimeoutMain, Throwable> ERROR =
				AtomicReferenceFieldUpdater.newUpdater(SampleTimeoutMain.class,
						Throwable.class,
						"error");

		volatile boolean done;

		volatile boolean cancelled;

		SampleTimeoutMain(
				CoreSubscriber<? super T> actual,
				Function<? super T, ? extends Publisher<U>> throttler
		) {
			this.actual = actual;
			this.ctx = actual.currentContext();
			this.throttler = throttler;
		}

		@Override
		public Stream<? extends Scannable> inners() {
			return Stream.of(Scannable.from(other));
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == Attr.TERMINATED) return done;
			if (key == Attr.CANCELLED) return cancelled;
			if (key == Attr.PARENT) return s;
			if (key == Attr.ERROR) return error;
			if (key == Attr.REQUESTED_FROM_DOWNSTREAM) return requested;
			if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;

			return InnerOperator.super.scanUnsafe(key);
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return actual;
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				Operators.addCap(REQUESTED, this, n);
			}
		}

		@Override
		public void cancel() {
			if (!cancelled) {
				cancelled = true;
				Operators.terminate(S, this);
				SampleTimeoutOther<?,?> last = OTHER.getAndSet(this, TERMINATED);
				if (last != TERMINATED) {
					last.cancel();
				}
			}
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.setOnce(S, this, s)) {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(T t) {

			SampleTimeoutOther<?,?> last = OTHER.get(this);

			if (last == TERMINATED) {
				return;
			}

			Publisher<U> p;

			try {
				p = Objects.requireNonNull(throttler.apply(t),
						"throttler returned a null publisher");
			} catch (Throwable e) {
				onError(Operators.onOperatorError(s, e, t, ctx));
				return;
			}

			SampleTimeoutOther<T, U> os = new SampleTimeoutOther<>(this, t);

			do {
				if (OTHER.compareAndSet(this, last, os)) {
					last.cancel();
					p = Operators.toFluxOrMono(p);
					p.subscribe(os);
					return;
				}
			} while((last = OTHER.get(this)) != TERMINATED);

			os.cancel(); // last == TERMINATED
		}

		void error(Throwable t, SampleTimeoutOther<?,?> o) {
			if (Exceptions.addThrowable(ERROR, this, t)) {
				done = true;
				terminate(actual, o);
			}
			else {
				Operators.onErrorDropped(t, ctx);
			}
		}

		@Override
		public void onError(Throwable t) {
			SampleTimeoutOther<?, ?> last = OTHER.getAndSet(this, TERMINATED);
			error(t, last);
		}

		@Override
		public void onComplete() {
			SampleTimeoutOther<?, ?> o = other;
			done = true;
			if (o.once == 1) {
				Subscriber<? super T> a = actual;
				if (!terminate(a, o)){
					a.onComplete();
				}
			}
		}

		void otherNext(SampleTimeoutOther<T, U> other) {

			final Subscriber<? super T> a = actual;

			boolean d = done;

			if (cancelOrTerminate(a, other, d)) {
				return;
			}

			next(a, other);

			if (d) {
				a.onComplete();
			}
		}

		void otherError(Throwable e, SampleTimeoutOther<T,U> o) {
			Operators.terminate(S, this);
			error(e, o);
		}


		private void next(Subscriber<? super T> a, SampleTimeoutOther<T, U> o) {
			long r = requested;
			if (r != 0) {
				a.onNext(o.value);
				if (r != Long.MAX_VALUE) {
					REQUESTED.decrementAndGet(this);
				}
			}
			else {
				cancel();
				o.cancel();

				Throwable e = Exceptions.failWithOverflow(
						"Could not emit value due to lack of requests");
				Exceptions.addThrowable(ERROR, this, e);
				e = Exceptions.terminate(ERROR, this);

				a.onError(e);
			}
		}

		private boolean cancelOrTerminate(Subscriber<?> a, SampleTimeoutOther<T, U> o, boolean done) {
			if (cancelled) {
				o.cancel();
				return true;
			}

			if (done) {
				return terminate(a, o);
			}
			return false;
		}

		boolean terminate(Subscriber<?> a, SampleTimeoutOther<?, ?> o) {
			Throwable e = Exceptions.terminate(ERROR, this);
			if (e != null && e != Exceptions.TERMINATED) {
				cancel();

				o.cancel();
				a.onError(e);
				return true;
			}
			return false;
		}
	}

	static final class SampleTimeoutOther<T, U> extends Operators.DeferredSubscription
			implements InnerConsumer<U> {

		final SampleTimeoutMain<T, U> main;

		final T value;


		volatile int once;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<SampleTimeoutOther> ONCE =
				AtomicIntegerFieldUpdater.newUpdater(SampleTimeoutOther.class, "once");

		SampleTimeoutOther(SampleTimeoutMain<T, U> main, T value) {
			this.main = main;
			this.value = value;
		}

		private static SampleTimeoutOther<Object, Object> sentinel() {
			final SampleTimeoutOther<Object, Object> dummy = new SampleTimeoutOther<>(null, null);
			dummy.once = 1;
			return dummy;
		}

		@Override
		public Context currentContext() {
			return main.currentContext();
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == Attr.TERMINATED) return once == 1;
			if (key == Attr.ACTUAL) return main;
			if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;

			return super.scanUnsafe(key);
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (set(s)) {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(U t) {
			if (ONCE.compareAndSet(this, 0, 1)) {
				super.cancel();
				main.otherNext(this);
			}
		}

		@Override
		public void onError(Throwable t) {
			if (ONCE.compareAndSet(this, 0, 1)) {
				main.otherError(t, this);
			}
			else {
				Operators.onErrorDropped(t, main.currentContext());
			}
		}

		@Override
		public void onComplete() {
			if (ONCE.compareAndSet(this, 0, 1)) {
				main.otherNext(this);
			}
		}

		@Override
		public void cancel() {
			if (ONCE.compareAndSet(this, 0, 1)) {
				Operators.onDiscard(this.value, main.ctx);
			}
			super.cancel();
		}

	}
}
