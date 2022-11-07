package nl.topicus.onderwijs.maven;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.InputSource;
import org.objectweb.asm.ClassReader;

public class CollectorTest<T extends ClassReader> implements Predicate<T>
{
	public static class X
	{
	}

	public static class Y extends X
	{
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
		ElementType.CONSTRUCTOR, ElementType.LOCAL_VARIABLE, ElementType.ANNOTATION_TYPE,
		ElementType.PACKAGE, ElementType.TYPE_PARAMETER, ElementType.TYPE_USE, ElementType.MODULE})
	private @interface Test1
	{
		Class< ? extends X> type() default Y.class;

		Test2[] nested() default {};

		ElementType z();
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
		ElementType.CONSTRUCTOR, ElementType.LOCAL_VARIABLE, ElementType.ANNOTATION_TYPE,
		ElementType.PACKAGE, ElementType.TYPE_PARAMETER, ElementType.TYPE_USE, ElementType.MODULE})
	private @interface Test2
	{
		String value() default "";
	}

	@Test2
	private @Test1(z = ElementType.PACKAGE) int field;

	@SuppressWarnings("unused")
	private Function< ? extends List< ? >, Map<String, Boolean>> field2;

	@SuppressWarnings("unused")
	@Test1(type = X.class, nested = {@Test2("5")}, z = ElementType.TYPE_USE)
	public static void main(String[] args) throws Exception
	{
		@Test2
		ClassReader reader = new ClassReader("org.slf4j.impl.OutputChoice$OutputChoiceType");
		ClassDependencyCollector collector = new ClassDependencyCollector();
		reader.accept(collector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		collector.getDeps().forEach(System.out::println);
		
		InputSource[][][] y = new InputSource[7][10][5];
		Class<?> u = ArtifactRepository.class;
	}

	@Override
	public boolean test(T t)
	{
		return false;
	}
}
