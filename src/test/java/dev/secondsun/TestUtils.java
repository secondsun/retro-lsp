package dev.secondsun;

import java.io.File;
import java.io.IOException;
import java.net.URI;

public class TestUtils {
    
    public static URI getTestFile(String string) {
        return new File(TestUtils.class.getClassLoader().getResource("includeTest/" + string).getFile()).toURI();
    }

    public static URI getTestDirURI() throws IOException {
        File file = new File(TestUtils.class.getClassLoader().getResource("includeTest/test.sgs").getFile());
        return file.getParentFile().getCanonicalFile().toURI();
    }
}
