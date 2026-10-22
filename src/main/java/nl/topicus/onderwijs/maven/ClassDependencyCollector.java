package nl.topicus.onderwijs.maven;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

public class ClassDependencyCollector extends ClassVisitor
{
	public Set<String> deps = new HashSet<>();

	private class AnnotationDependencyCollector extends AnnotationVisitor
	{
		public AnnotationDependencyCollector()
		{
			super(Opcodes.ASM9);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String name, String descriptor)
		{
			addTypeDescriptor(descriptor);
			return this;
		}

		@Override
		public AnnotationVisitor visitArray(String name)
		{
			return this;
		}

		@Override
		public void visit(String name, Object value)
		{
			if (value instanceof Type)
			{
				addType((Type) value);
			}
		}

		@Override
		public void visitEnum(String name, String descriptor, String value)
		{
			addTypeDescriptor(descriptor);
		}
	}

	private class FieldDependencyCollector extends FieldVisitor
	{
		public FieldDependencyCollector()
		{
			super(Opcodes.ASM9);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible)
		{
			addTypeDescriptor(descriptor);
			return new AnnotationDependencyCollector();
		}

		@Override
		public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
				String descriptor, boolean visible)
		{
			addTypeDescriptor(descriptor);
			return new AnnotationDependencyCollector();
		}
	}

	private class MethodDependencyCollector extends MethodVisitor
	{
		public MethodDependencyCollector()
		{
			super(Opcodes.ASM9);
		}

		@Override
		public AnnotationVisitor visitAnnotationDefault()
		{
			return new AnnotationDependencyCollector();
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible)
		{
			addTypeDescriptor(descriptor);
			return new AnnotationDependencyCollector();
		}

		@Override
		public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
				String descriptor, boolean visible)
		{
			addTypeDescriptor(descriptor);
			return new AnnotationDependencyCollector();
		}

		@Override
		public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor,
				boolean visible)
		{
			addTypeDescriptor(descriptor);
			return new AnnotationDependencyCollector();
		}

		@Override
		public void visitLdcInsn(Object value)
		{
			addConstant(value);
		}

		@Override
		public void visitTypeInsn(int opcode, String type)
		{
			if (type.startsWith("["))
				addTypeDescriptor(type);
			else
				addClass(type);
		}

		@Override
		public void visitFieldInsn(int opcode, String owner, String name, String descriptor)
		{
			addOwner(owner);
			addTypeDescriptor(descriptor);
		}

		@Override
		public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
				boolean isInterface)
		{
			addOwner(owner);
			addMethodDescriptor(descriptor);
		}

		@Override
		public void visitInvokeDynamicInsn(String name, String descriptor,
				Handle bootstrapMethodHandle, Object... bootstrapMethodArguments)
		{
			addMethodDescriptor(descriptor);
			addConstant(bootstrapMethodHandle);
			Stream.of(bootstrapMethodArguments).forEach(ClassDependencyCollector.this::addConstant);
		}

		@Override
		public void visitTryCatchBlock(Label start, Label end, Label handler, String type)
		{
			addClass(type);
		}

		@Override
		public void visitMultiANewArrayInsn(String descriptor, int numDimensions)
		{
			addTypeDescriptor(descriptor);
		}

		@Override
		public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath,
				String descriptor, boolean visible)
		{
			addTypeDescriptor(descriptor);
			return new AnnotationDependencyCollector();
		}

		@Override
		public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath,
				String descriptor, boolean visible)
		{
			addTypeDescriptor(descriptor);
			return new AnnotationDependencyCollector();
		}

		@Override
		public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
				Label[] start, Label[] end, int[] index, String descriptor, boolean visible)
		{
			addTypeDescriptor(descriptor);
			return new AnnotationDependencyCollector();
		}
	}

	private class RecordComponentDependencyCollector extends RecordComponentVisitor
	{
		public RecordComponentDependencyCollector()
		{
			super(Opcodes.ASM9);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible)
		{
			addTypeDescriptor(descriptor);
			return new AnnotationDependencyCollector();
		}

		@Override
		public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
				String descriptor, boolean visible)
		{
			addTypeDescriptor(descriptor);
			return new AnnotationDependencyCollector();
		}
	}

	private class SignatureDependencyCollector extends SignatureVisitor
	{
		/** A nested class in a signature only names itself, relative to the class before it. */
		private String currentClass;

		public SignatureDependencyCollector()
		{
			super(Opcodes.ASM9);
		}

		@Override
		public SignatureVisitor visitClassBound()
		{
			return this;
		}

		@Override
		public SignatureVisitor visitInterfaceBound()
		{
			return this;
		}

		@Override
		public SignatureVisitor visitSuperclass()
		{
			return this;
		}

		@Override
		public SignatureVisitor visitInterface()
		{
			return this;
		}

		@Override
		public SignatureVisitor visitParameterType()
		{
			return this;
		}

		@Override
		public SignatureVisitor visitReturnType()
		{
			return this;
		}

		@Override
		public SignatureVisitor visitExceptionType()
		{
			return this;
		}

		@Override
		public SignatureVisitor visitArrayType()
		{
			return this;
		}

		@Override
		public void visitClassType(String name)
		{
			currentClass = name;
			addClass(name);
		}

		@Override
		public void visitInnerClassType(String name)
		{
			currentClass = currentClass + "$" + name;
			addClass(currentClass);
		}

		@Override
		public SignatureVisitor visitTypeArgument(char wildcard)
		{
			return new SignatureDependencyCollector();
		}
	}

	public ClassDependencyCollector()
	{
		super(Opcodes.ASM9);
	}

	@Override
	public void visit(int version, int access, String name, String signature, String superName,
			String[] interfaces)
	{
		addSignature(signature);
		addClass(superName);
		Stream.of(interfaces).forEach(this::addClass);
	}

	@Override
	public AnnotationVisitor visitAnnotation(String descriptor, boolean visible)
	{
		addTypeDescriptor(descriptor);
		return new AnnotationDependencyCollector();
	}

	@Override
	public FieldVisitor visitField(int access, String name, String descriptor, String signature,
			Object value)
	{
		addTypeDescriptor(descriptor);
		addTypeSignature(signature);
		return new FieldDependencyCollector();
	}

	@Override
	public void visitInnerClass(String name, String outerName, String innerName, int access)
	{
		addClass(outerName);
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
			String[] exceptions)
	{
		addMethodDescriptor(descriptor);
		addSignature(signature);
		if (exceptions != null)
			Stream.of(exceptions).forEach(this::addClass);
		return new MethodDependencyCollector();
	}

	@Override
	public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor,
			boolean visible)
	{
		addTypeDescriptor(descriptor);
		return new AnnotationDependencyCollector();
	}

	@Override
	public RecordComponentVisitor visitRecordComponent(String name, String descriptor,
			String signature)
	{
		addTypeDescriptor(descriptor);
		addTypeSignature(signature);
		return new RecordComponentDependencyCollector();
	}

	private void addTypeSignature(String signature)
	{
		if (signature == null)
			return;
		SignatureReader sigReader = new SignatureReader(signature);
		sigReader.acceptType(new SignatureDependencyCollector());
	}

	private void addSignature(String signature)
	{
		if (signature == null)
			return;
		SignatureReader sigReader = new SignatureReader(signature);
		sigReader.accept(new SignatureDependencyCollector());
	}

	private void addTypeDescriptor(String descriptor)
	{
		addType(Type.getType(descriptor));
	}

	private void addMethodDescriptor(String descriptor)
	{
		addType(Type.getReturnType(descriptor));
		Stream.of(Type.getArgumentTypes(descriptor)).forEach(this::addType);
	}

	private void addOwner(String owner)
	{
		if (owner.startsWith("["))
			addTypeDescriptor(owner);
		else
			addClass(owner);
	}

	/** A constant of an ldc instruction or a bootstrap method argument. */
	private void addConstant(Object value)
	{
		if (value instanceof Type)
		{
			Type type = (Type) value;
			if (type.getSort() == Type.METHOD)
				addMethodDescriptor(type.getDescriptor());
			else
				addType(type);
		}
		else if (value instanceof Handle)
		{
			Handle handle = (Handle) value;
			addOwner(handle.getOwner());
			if (handle.getTag() <= Opcodes.H_PUTSTATIC)
				addTypeDescriptor(handle.getDesc());
			else
				addMethodDescriptor(handle.getDesc());
		}
		else if (value instanceof ConstantDynamic)
		{
			ConstantDynamic constant = (ConstantDynamic) value;
			addTypeDescriptor(constant.getDescriptor());
			addConstant(constant.getBootstrapMethod());
			for (int i = 0; i < constant.getBootstrapMethodArgumentCount(); i++)
				addConstant(constant.getBootstrapMethodArgument(i));
		}
	}

	private void addType(Type type)
	{
		if (type.getSort() == Type.ARRAY)
			addType(type.getElementType());
		else if (type.getSort() == Type.OBJECT)
		{
			String descriptor = type.getDescriptor();
			addClass(descriptor.substring(1, descriptor.length() - 1));
		}
	}

	private void addClass(String className)
	{
		if (className != null)
		{
			deps.add(className);
		}
	}

	public Set<String> getDeps()
	{
		return deps;
	}
}
