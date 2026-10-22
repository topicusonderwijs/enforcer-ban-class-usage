# ban-class-usage

A rule for the [Maven Enforcer Plugin](https://maven.apache.org/enforcer/maven-enforcer-plugin/) that fails the build when a dependency uses a banned class. Where `bannedDependencies` looks at which artifacts are on the classpath, `banClassUsage` looks inside them: it reads the bytecode of every dependency and reports the banned classes each one references.

The typical use is a migration from `javax` to `jakarta`. After the migration a library can still be compiled against `javax.servlet` or `javax.persistence` without depending on those APIs in its POM, and then it fails at runtime. This rule finds that during the build.

## Usage

Add the rule as a dependency of the enforcer plugin and configure it under `rules`:

```xml
<plugin>
	<groupId>org.apache.maven.plugins</groupId>
	<artifactId>maven-enforcer-plugin</artifactId>
	<dependencies>
		<dependency>
			<groupId>nl.topicus.onderwijs.maven</groupId>
			<artifactId>ban-class-usage</artifactId>
			<version>1.6</version>
		</dependency>
	</dependencies>
	<executions>
		<execution>
			<id>ban-class-usage</id>
			<goals>
				<goal>enforce</goal>
			</goals>
			<configuration>
				<rules>
					<banClassUsage>
						<scopes>
							<scope>compile</scope>
							<scope>runtime</scope>
						</scopes>
						<bannedClasses>
							<bannedClass>javax.servlet.*</bannedClass>
							<bannedClass>javax.persistence.*</bannedClass>
							<bannedClass>javax.annotation.PostConstruct</bannedClass>
						</bannedClasses>
						<dependencies>
							<dependency>
								<groupId>com.example</groupId>
								<artifactId>legacy-*</artifactId>
								<ignoreClasses>
									<ignoreClass>javax.servlet.http.*</ignoreClass>
								</ignoreClasses>
							</dependency>
						</dependencies>
					</banClassUsage>
				</rules>
			</configuration>
		</execution>
	</executions>
</plugin>
```

The rule uses the rule API of maven-enforcer-plugin 3.2.1 and later, and requires Java 11 or later. Releases are published to `https://repo.topicusonderwijs.nl/artifactory/libs-releases-local`.

## Configuration

### `bannedClasses`

Fully qualified class names, with dots between the package parts and `$` before a nested class (`com.example.Outer$Inner`).

- A name that ends in `*` is a prefix: `javax.servlet.*` bans every class in `javax.servlet` and its subpackages, and `javax.annotation.Resource*` bans both `Resource` and `Resources`.
- Any other name must match exactly. A `*` that is not at the end has no special meaning.

### `scopes`

The dependency scopes to check, for example `compile`, `runtime` and `test`. A transitive dependency is checked in the scope it ends up with in the project. Without `scopes`, dependencies in every scope are checked.

### `dependencies`

Exceptions for specific dependencies. Every `dependency` element has these fields:

| Field | Meaning |
|---|---|
| `groupId`, `artifactId`, `classifier`, `type` | Patterns on the coordinates of the dependency. An omitted field matches anything. |
| `ignoreClasses` | Banned classes that this dependency may use. Required; use `<ignoreClass>*</ignoreClass>` to allow everything. |

All patterns must match the whole value, and support two wildcards: `*` matches any sequence of characters, including dots, and `?` matches a single character. So `javax.servlet.*` in `ignoreClasses` also covers the subpackages of `javax.servlet`.

## What is checked

The rule builds the full dependency graph of the project, resolves every artifact in it and filters them by `scopes`. Of each artifact it reads every `.class` entry of the jar. A dependency that is not a jar, such as a reactor module whose classes have not been packaged yet, is skipped.

A class counts as used when it appears in:

- the superclass, the implemented interfaces or the outer class of a nested class;
- the types and generic signatures of fields and methods, and the exceptions a method declares;
- annotations, including class literals and enum constants in annotation values;
- the instructions of method bodies: `new`, casts and `instanceof`, array creation, class literals, and the parameter and return types of called methods, the types of accessed fields, and the types in the descriptor of `invokedynamic` call sites such as lambdas.

Debug information such as local variable tables is ignored. A class file that can't be read is skipped with a warning; a jar that can't be opened fails the build.

When a dependency uses banned classes, the build fails with a list per dependency:

```
Rule 0: nl.topicus.onderwijs.maven.BanClassUsage failed with message:
One or more dependencies use classes that are banned:

  Banned classes found in com.nimbusds:oauth2-oidc-sdk:jar:11.38.2:compile:
    javax.servlet.ServletRequest
    javax.servlet.http.HttpServletRequest
    javax.servlet.http.HttpServletResponse
```

Run Maven with `-X` to see which artifacts and classes are checked. Skip the rule for a single build with `-Denforcer.skipRules=banClassUsage`.

## Building

```bash
mvn verify
```

The project inherits its plugin and dependency versions from `onderwijs-parent`.
