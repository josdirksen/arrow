package arrow.effects

import arrow.Kind
import arrow.core.*
import arrow.effects.CoroutineContextRx2Scheduler.asScheduler
import arrow.effects.typeclasses.Disposable
import arrow.effects.typeclasses.ExitCase
import arrow.effects.typeclasses.Fiber
import arrow.higherkind
import arrow.typeclasses.Applicative
import io.reactivex.Single
import io.reactivex.SingleEmitter
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.ReplaySubject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

fun <A> Single<A>.k(): SingleK<A> = SingleK(this)

fun <A> SingleKOf<A>.value(): Single<A> = fix().single

@higherkind
data class SingleK<A>(val single: Single<A>) : SingleKOf<A>, SingleKKindedJ<A> {

  fun <B> map(f: (A) -> B): SingleK<B> =
    single.map(f).k()

  fun <B> ap(fa: SingleKOf<(A) -> B>): SingleK<B> =
    flatMap { a -> fa.fix().map { ff -> ff(a) } }

  fun <B> flatMap(f: (A) -> SingleKOf<B>): SingleK<B> =
    single.flatMap { f(it).value() }.k()

  fun <B> foldLeft(b: B, f: (B, A) -> B): B =
    f(b, single.blockingGet())

  fun <B> foldRight(lb: Eval<B>, f: (A, Eval<B>) -> Eval<B>): Eval<B> =
    f(single.blockingGet(), lb)

  fun <G, B> traverse(GA: Applicative<G>, f: (A) -> Kind<G, B>): Kind<G, SingleK<B>> = GA.run {
    return try {
      f(single.blockingGet()).map { SingleK.just(it) }
    } catch (e: Throwable) {
      just(SingleK.raiseError(e))
    }
  }

  fun exists(predicate: Predicate<A>): Boolean =
    foldLeft(false) { _, a -> predicate(a) }

  fun forall(predicate: Predicate<A>): Boolean =
    foldLeft(true) { _, a -> predicate(a) }

  /**
   * A way to safely acquire a resource and release in the face of errors and cancellation.
   * It uses [ExitCase] to distinguish between different exit cases when releasing the acquired resource.
   *
   * @param use is the action to consume the resource and produce an [SingleK] with the result.
   * Once the resulting [SingleK] terminates, either successfully, error or disposed,
   * the [release] function will run to clean up the resources.
   *
   * @param release the allocated resource after the resulting [SingleK] of [use] is terminates.
   *
   * {: data-executable='true'}
   * ```kotlin:ank
   * import arrow.effects.*
   * import arrow.effects.typeclasses.ExitCase
   *
   * class File(url: String) {
   *   fun open(): File = this
   *   fun close(): Unit {}
   *   fun content(): SingleK<String> =
   *     SingleK.just("This file contains some interesting content!")
   * }
   *
   * fun openFile(uri: String): SingleK<File> = SingleK { File(uri).open() }
   * fun closeFile(file: File): SingleK<Unit> = SingleK { file.close() }
   *
   * fun main(args: Array<String>) {
   *   //sampleStart
   *   val safeComputation = openFile("data.json").bracketCase(
   *     release = { file, exitCase ->
   *       when (exitCase) {
   *         is ExitCase.Completed -> { /* do something */ }
   *         is ExitCase.Canceled -> { /* do something */ }
   *         is ExitCase.Error -> { /* do something */ }
   *       }
   *       closeFile(file)
   *     },
   *     use = { file -> file.content() }
   *   )
   *   //sampleEnd
   *   println(safeComputation)
   * }
   *  ```
   */
  fun <B> bracketCase(use: (A) -> SingleKOf<B>, release: (A, ExitCase<Throwable>) -> SingleKOf<Unit>): SingleK<B> =
    SingleK(Single.create<B> { emitter ->
      single.subscribe({ a ->
        if (emitter.isDisposed) {
          release(a, ExitCase.Canceled).fix().single.subscribe({}, emitter::onError)
        } else {
          emitter.setDisposable(use(a).fix()
            .flatMap { b -> release(a, ExitCase.Completed).fix().map { b } }
            .handleErrorWith { e -> release(a, ExitCase.Error(e)).fix().flatMap { SingleK.raiseError<B>(e) } }
            .single
            .doOnDispose { release(a, ExitCase.Canceled).fix().single.subscribe({}, emitter::onError) }
            .subscribe(emitter::onSuccess, emitter::onError))
        }
      }, emitter::onError)
    })

  fun handleErrorWith(function: (Throwable) -> SingleKOf<A>): SingleK<A> =
    single.onErrorResumeNext { t: Throwable -> function(t).value() }.k()

  fun continueOn(ctx: CoroutineContext): SingleK<A> =
    single.observeOn(ctx.asScheduler()).k()

  fun startF(ctx: CoroutineContext): SingleK<Fiber<ForSingleK, A>> = SingleK {
    val join = ReplaySubject.create<A>()
    val active = AtomicBoolean(true)
    val scheduler = ctx.asScheduler()

    val disposable = single
      .subscribeOn(scheduler)
      .observeOn(scheduler)
      .subscribe({
        if (active.getAndSet(false)) {
          join.onNext(it)
          join.onComplete()
        }
      }, {
        if (active.getAndSet(false)) join.onError(it)
      })

    val cancel = SingleK {
      disposable.dispose()
      if (active.getAndSet(false)) join.onError(ConnectionCancellationException())
    }

    Fiber(join.firstOrError().k(), cancel)
  }

  fun runAsync(cb: (Either<Throwable, A>) -> SingleKOf<Unit>): SingleK<Unit> =
    single.flatMap { cb(Right(it)).value() }.onErrorResumeNext { cb(Left(it)).value() }.k()

  fun runAsyncCancellable(cb: (Either<Throwable, A>) -> SingleKOf<Unit>): SingleK<Disposable> =
    Single.fromCallable {
      val disposable: io.reactivex.disposables.Disposable = runAsync(cb).value().subscribe()
      val dispose: () -> Unit = { disposable.dispose() }
      dispose
    }.k()

  override fun equals(other: Any?): Boolean =
    when (other) {
      is SingleK<*> -> this.single == other.single
      is Single<*> -> this.single == other
      else -> false
    }

  override fun hashCode(): Int = single.hashCode()

  companion object {
    fun <A> just(a: A): SingleK<A> =
      Single.just(a).k()

    fun <A> raiseError(t: Throwable): SingleK<A> =
      Single.error<A>(t).k()

    operator fun <A> invoke(fa: () -> A): SingleK<A> =
      defer { just(fa()) }

    fun <A> defer(fa: () -> SingleKOf<A>): SingleK<A> =
      Single.defer { fa().value() }.k()

    /**
     * Creates a [SingleK] that'll run [SingleKProc].
     *
     * {: data-executable='true'}
     *
     * ```kotlin:ank
     * import arrow.core.Either
     * import arrow.core.right
     * import arrow.effects.SingleK
     * import arrow.effects.SingleKConnection
     * import arrow.effects.value
     *
     * class Resource {
     *   fun asyncRead(f: (String) -> Unit): Unit = f("Some value of a resource")
     *   fun close(): Unit = Unit
     * }
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   val result = SingleK.async { conn: SingleKConnection, cb: (Either<Throwable, String>) -> Unit ->
     *     val resource = Resource()
     *     conn.push(SingleK { resource.close() })
     *     resource.asyncRead { value -> cb(value.right()) }
     *   }
     *   //sampleEnd
     *   result.value().subscribe(::println, ::println)
     * }
     * ```
     */
    fun <A> async(fa: SingleKProc<A>): SingleK<A> =
      SingleK(Single.create<A> { emitter ->
        val conn = SingleKConnection()
        //On disposing of the upstream stream this will be called by `setCancellable` so check if upstream is already disposed or not because
        //on disposing the stream will already be in a terminated state at this point so calling onError, in a terminated state, will blow everything up.
        conn.push(SingleK { if (!emitter.isDisposed) emitter.onError(ConnectionCancellationException()) })
        emitter.setCancellable { conn.cancel().value().subscribe() }

        fa(conn) { either: Either<Throwable, A> ->
          either.fold({
            emitter.onError(it)
          }, {
            emitter.onSuccess(it)
          })
        }
      })

    fun <A> asyncF(fa: SingleKProcF<A>): SingleK<A> =
      SingleK(Single.create { emitter: SingleEmitter<A> ->
        val conn = SingleKConnection()
        //On disposing of the upstream stream this will be called by `setCancellable` so check if upstream is already disposed or not because
        //on disposing the stream will already be in a terminated state at this point so calling onError, in a terminated state, will blow everything up.
        conn.push(SingleK { if (!emitter.isDisposed) emitter.onError(ConnectionCancellationException()) })
        emitter.setCancellable { conn.cancel().value().subscribe() }

        val registerCancel = CompositeDisposable()
        conn.push(SingleK { registerCancel.dispose() })
        registerCancel.add(fa(conn) { either: Either<Throwable, A> ->
          either.fold({
            emitter.onError(it)
          }, {
            emitter.onSuccess(it)
          })
        }.fix().single.subscribe({
          conn.pop()
        }, emitter::onError))
      })

    tailrec fun <A, B> tailRecM(a: A, f: (A) -> SingleKOf<Either<A, B>>): SingleK<B> {
      val either = f(a).value().blockingGet()
      return when (either) {
        is Either.Left -> tailRecM(either.a, f)
        is Either.Right -> Single.just(either.b).k()
      }
    }
  }
}

/**
 * Runs the [SingleK] asynchronously and then runs the cb.
 * Catches all errors that may be thrown in await. Errors from cb will still throw as expected.
 *
 * {: data-executable='true'}
 *
 * ```kotlin:ank
 * import arrow.core.Either
 * import arrow.effects.SingleK
 * import arrow.effects.unsafeRunAsync
 *
 * fun main(args: Array<String>) {
 *   //sampleStart
 *   SingleK.just(1).unsafeRunAsync { either: Either<Throwable, Int> ->
 *     either.fold({ t: Throwable ->
 *       println(t)
 *     }, { i: Int ->
 *       println("DONE WITH $i")
 *     })
 *   }
 *   //sampleEnd
 * }
 * ```
 */
fun <A> SingleKOf<A>.unsafeRunAsync(cb: (Either<Throwable, A>) -> Unit): Unit =
  value().subscribe({ cb(Right(it)) }, { cb(Left(it)) }).let { }

/**
 * Runs this [SingleK] with [Single.blockingGet]. Does not handle errors at all, rethrowing them if they happen.
 *
 * {: data-executable='true'}
 *
 * ```kotlin:ank
 * import arrow.effects.DeferredK
 * import arrow.effects.unsafeRunSync
 *
 * fun main(args: Array<String>) {
 *   //sampleStart
 *   val result: DeferredK<String> = DeferredK.raiseError<String>(Exception("BOOM"))
 *   //sampleEnd
 *   println(result.unsafeRunSync())
 * }
 * ```
 */
fun <A> SingleKOf<A>.unsafeRunSync(): A =
  value().blockingGet()