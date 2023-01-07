package dev.secondsun;


import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

import dev.secondsun.tm4e4lsp.util.Util;

public class UtilTest {
    
@Test
public void trimCompletionTest() {
    var completion = Util.trimCompletion(".InclUdE          \"h", ".include \"himem.i\"");
    assertEquals("imem.i\"", completion);


    completion = Util.trimCompletion(".InclUdE", ".include \"himem.i\"");
    assertEquals(" \"himem.i\"", completion);

}

}
