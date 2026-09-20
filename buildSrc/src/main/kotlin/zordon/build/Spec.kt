package zordon.build

/**
 * Liga uma classe do build-logic à SPEC que a originou.
 *
 * <p>É a mesma marca de `zordon.api.trace.Spec`, declarada aqui porque o
 * build-logic não pode depender dos módulos que ele constrói.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class Spec(val value: String)
