package com.inzent.airestclient.generator;

import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.springframework.stereotype.Component;

/**
 * 생성된 소스를 디스크 없이 메모리에서 컴파일한다(생성 결과 검증 및 통신 테스트 시 동적 로딩용).
 * 컴파일 클래스패스는 현재 JVM의 클래스패스를 사용하므로, 앱이 Gradle/IDE 등 일반 클래스패스로 실행될 때 동작한다
 * (중첩 JAR을 쓰는 Spring Boot 실행 JAR 형태에서는 클래스패스 구성이 별도로 필요하다).
 */
@Component
public class InMemoryCompiler {

  /** 컴파일된 클래스 바이트 모음. 격리된 ClassLoader로 로딩할 수 있다. */
  public static final class CompiledClasses {

    private final Map<String, byte[]> bytesByName;

    CompiledClasses(Map<String, byte[]> bytesByName) {
      this.bytesByName = bytesByName;
    }

    public boolean contains(String className) {
      return bytesByName.containsKey(className);
    }

    /** 매번 새로운 ClassLoader를 만들어 서로 다른 생성 결과가 섞이지 않게 한다. */
    public ClassLoader newClassLoader(ClassLoader parent) {
      return new ClassLoader(parent) {
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
          byte[] bytes = bytesByName.get(name);
          if (bytes == null) {
            throw new ClassNotFoundException(name);
          }
          return defineClass(name, bytes, 0, bytes.length);
        }
      };
    }
  }

  public record CompileResult(boolean success, List<String> diagnostics, CompiledClasses classes) {}

  /** @param sourcesByPath "com/x/Foo.java" → 소스 코드 */
  public CompileResult compile(Map<String, String> sourcesByPath) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new BusinessException(ErrorCode.GENERATION_FAILED,
          "JDK가 아닌 JRE로 실행 중이라 컴파일할 수 없습니다. JDK 17 이상으로 실행하세요.");
    }

    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    Map<String, ByteArrayOutputStream> output = new HashMap<>();
    try (StandardJavaFileManager standard =
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
      JavaFileManager fileManager = new MemoryFileManager(standard, output);

      List<JavaFileObject> units = new ArrayList<>();
      sourcesByPath.forEach((path, code) -> units.add(new SourceFile(path, code)));

      List<String> options = List.of("-proc:none", "-Xlint:none", "--release", "17",
          "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"));
      boolean success = Boolean.TRUE.equals(
          compiler.getTask(null, fileManager, diagnostics, options, null, units).call());

      List<String> messages = diagnostics.getDiagnostics().stream()
          .filter(d -> d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
          .map(InMemoryCompiler::format)
          .toList();

      Map<String, byte[]> classes = new HashMap<>();
      output.forEach((name, stream) -> classes.put(name, stream.toByteArray()));
      return new CompileResult(success, messages, new CompiledClasses(classes));
    } catch (java.io.IOException e) {
      throw new BusinessException(ErrorCode.GENERATION_FAILED, "컴파일러를 초기화하지 못했습니다.", e);
    }
  }

  private static String format(Diagnostic<? extends JavaFileObject> d) {
    String source = d.getSource() == null ? "" : d.getSource().getName().replace("/", "/");
    return (source.isEmpty() ? "" : source + ":" + d.getLineNumber() + " ") + d.getMessage(null);
  }

  private static final class SourceFile extends SimpleJavaFileObject {

    private final String code;

    SourceFile(String path, String code) {
      super(URI.create("string:///" + path), Kind.SOURCE);
      this.code = code;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return code;
    }
  }

  private static final class ClassFile extends SimpleJavaFileObject {

    private final ByteArrayOutputStream stream;

    ClassFile(String className, ByteArrayOutputStream stream) {
      super(URI.create("mem:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
      this.stream = stream;
    }

    @Override
    public OutputStream openOutputStream() {
      return stream;
    }
  }

  private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    private final Map<String, ByteArrayOutputStream> output;

    MemoryFileManager(StandardJavaFileManager delegate, Map<String, ByteArrayOutputStream> output) {
      super(delegate);
      this.output = output;
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
        FileObject sibling) {
      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      output.put(className, stream);
      return new ClassFile(className, stream);
    }
  }
}
