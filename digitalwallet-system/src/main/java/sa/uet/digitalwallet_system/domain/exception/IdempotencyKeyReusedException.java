package sa.uet.digitalwallet_system.domain.exception;

public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String key) {
        super("Idempotency key " + key + " already used but transaction doesn't match");
    }
}
