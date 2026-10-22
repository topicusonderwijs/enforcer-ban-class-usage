package nl.topicus.onderwijs.maven;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Matches class names against the configured banned classes, and tells from the raw bytes of a
 * class file whether it can reference a banned class at all, so most class files never need to be
 * parsed.
 */
class BannedClassMatcher
{
	private final List<String> bannedClasses;

	private final List<String> prefixes = new ArrayList<>();

	private final Set<String> names = new HashSet<>();

	/**
	 * The banned classes in internal form, indexed by their first byte. Null when the class file
	 * bytes can't be used to rule out a match.
	 */
	private final byte[][][] needlesByFirstByte;

	BannedClassMatcher(List<String> bannedClasses)
	{
		this.bannedClasses = List.copyOf(bannedClasses);
		Set<String> needles = new LinkedHashSet<>();
		for (String bannedClass : bannedClasses)
		{
			String name = bannedClass.endsWith("*")
				? bannedClass.substring(0, bannedClass.length() - 1) : bannedClass;
			if (bannedClass.endsWith("*"))
				prefixes.add(name);
			else
				names.add(name);
			needles.add(name.replace('.', '/'));
		}
		needlesByFirstByte = indexByFirstByte(needles);
	}

	List<String> getBannedClasses()
	{
		return bannedClasses;
	}

	boolean matches(String classname)
	{
		if (names.contains(classname))
			return true;
		for (String prefix : prefixes)
		{
			if (classname.startsWith(prefix))
				return true;
		}
		return false;
	}

	/**
	 * Every class a class file references is spelled out in internal form in a UTF8 entry of its
	 * constant pool, so a class file whose constant pool does not contain a banned name can't
	 * reference a banned class. A false positive only costs a full parse.
	 */
	boolean mayReferenceBannedClass(byte[] classFile, int constantPoolStart, int constantPoolEnd)
	{
		if (needlesByFirstByte == null)
			return true;
		for (int i = constantPoolStart; i < constantPoolEnd; i++)
		{
			byte[][] candidates = needlesByFirstByte[classFile[i] & 0xff];
			if (candidates == null)
				continue;
			for (byte[] needle : candidates)
			{
				if (regionMatches(classFile, i, constantPoolEnd, needle))
					return true;
			}
		}
		return false;
	}

	private static boolean regionMatches(byte[] bytes, int offset, int end, byte[] needle)
	{
		if (end - offset < needle.length)
			return false;
		for (int j = 1; j < needle.length; j++)
		{
			if (bytes[offset + j] != needle[j])
				return false;
		}
		return true;
	}

	private static byte[][][] indexByFirstByte(Set<String> needles)
	{
		for (String needle : needles)
		{
			// Modified UTF-8 only equals ASCII for these characters.
			if (needle.isEmpty() || !needle.chars().allMatch(c -> c > 0 && c < 0x80))
				return null;
		}

		List<List<byte[]>> index = new ArrayList<>();
		for (int i = 0; i < 256; i++)
			index.add(new ArrayList<>());
		for (String needle : needles)
		{
			// A needle that contains another needle is found through the shorter one.
			boolean covered = needles.stream()
				.anyMatch(other -> other.length() < needle.length() && needle.contains(other));
			if (!covered)
				index.get(needle.charAt(0)).add(needle.getBytes(StandardCharsets.US_ASCII));
		}

		byte[][][] result = new byte[256][][];
		for (int i = 0; i < 256; i++)
		{
			if (!index.get(i).isEmpty())
				result[i] = index.get(i).toArray(new byte[0][]);
		}
		return result;
	}
}
