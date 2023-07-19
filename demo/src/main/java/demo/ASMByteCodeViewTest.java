package demo;

import java.util.Arrays;

/**
 * 安装asm bytecode outline可以看这个文件的字节码和asm转换成java代码
 */
public class ASMByteCodeViewTest {

    int code;

    public ASMByteCodeViewTest(int code) {
        this.code = code;
    }

    public void print(String[] array) {
        long begin = System.currentTimeMillis();
        System.out.println(begin);
        System.out.println(Arrays.toString(array));
        System.out.println(System.currentTimeMillis() - begin);

        methodOnBegin(1,this.getClass().getClassLoader(),null,null,null,null,null);
    }

    public static void methodOnBegin(int adviceId, ClassLoader loader, String className, String methodName, String methodDesc, Object target, Object[] args) {
    }


}

