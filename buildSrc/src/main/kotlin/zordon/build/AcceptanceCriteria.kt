package zordon.build

/**
 * Liga um teste do build-logic ao critério de aceite que ele valida.
 *
 * <p>É a mesma marca de `zordon.api.trace.AcceptanceCriteria`, declarada aqui
 * porque o build-logic não pode depender dos módulos que ele constrói.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class AcceptanceCriteria(val value: String)
