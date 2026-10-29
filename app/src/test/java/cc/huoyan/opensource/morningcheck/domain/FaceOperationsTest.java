package cc.huoyan.opensource.morningcheck.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class FaceOperationsTest {
    @Test public void oldEmptyQueryCannotEraseRegistrationEvenAfterWriteFinishes() {
        FaceOperations operations = new FaceOperations();
        long beforeRegister = operations.version(21);
        assertTrue(operations.acceptsQuery(21, beforeRegister));
        assertTrue(operations.beginWrite(21));
        assertFalse(operations.acceptsQuery(21, beforeRegister));
        operations.finishWrite(21);
        assertFalse(operations.acceptsQuery(21, beforeRegister));
        assertTrue(operations.acceptsQuery(21, operations.version(21)));
    }

    @Test public void separateEmployeesDoNotBlockEachOtherAndSameEmployeeCannotWriteTwice() {
        FaceOperations operations = new FaceOperations();
        long otherEmployee = operations.version(22);
        assertTrue(operations.beginWrite(21));
        assertFalse(operations.beginWrite(21));
        assertTrue(operations.acceptsQuery(22, otherEmployee));
        operations.finishWrite(21);
        assertTrue(operations.beginWrite(21));
    }
}
