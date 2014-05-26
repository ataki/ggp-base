package org.ggp.base.util.propnet.factory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.propnet.architecture.CompiledPropNet;
import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.architecture.PropNet;
import org.ggp.base.util.propnet.architecture.components.And;
import org.ggp.base.util.propnet.architecture.components.Constant;
import org.ggp.base.util.propnet.architecture.components.Not;
import org.ggp.base.util.propnet.architecture.components.Or;
import org.ggp.base.util.propnet.architecture.components.Proposition;
import org.ggp.base.util.propnet.architecture.components.Transition;

public class CompiledPropNetFactory {

	private static final String FILE_HEADER = "/* WARNING: This file was automatically generated. DO NOT EDIT! */";
	private static final String SRC_DIR = "src/org/ggp/base/util/propnet/architecture/";
	private static final String DEST_DIR = "bin";

	private static final String PACKAGE = "org.ggp.base.util.propnet.architecture";
	private static final String PACKAGE_HEADER = "package "+PACKAGE+";";
	private static final String INCLUDES = "import org.ggp.base.util.propnet.architecture.CompiledPropNet;\n"
										 + "import org.ggp.base.util.propnet.architecture.components.Proposition;\n"
										 + "import java.util.Map;";

	private static final String UPDATE_DECL = "\tpublic void update() {";

	private static final String CLASS_POSTFIX = "PropNet";

	/**
	 * Creates a PropNet for the game with the given description.
	 *
	 * @throws InterruptedException if the thread is interrupted during
	 * PropNet creation.
	 */
	public static PropNet create(String gameName, List<Gdl> description) throws InterruptedException {
		return OptimizingPropNetFactory.create(description, false);
	}

	public static PropNet create(String gameName, List<Gdl> description, boolean verbose) throws InterruptedException {
		PropNet opt = OptimizingPropNetFactory.create(description, verbose);

		compile(gameName, opt);

		return opt;
	}

	private static void writeLine(BufferedWriter out, String line) throws IOException {
		out.write(line + "\n");
	}

	private static StringBuilder generateAssignment(Component c, Map<Proposition,Integer> indexMap) {
		StringBuilder sb = new StringBuilder();

		sb.append("(byte)");

		if (c instanceof Not) {
			sb.append("(1 ^ ");
		}

		sb.append("(");

		if (c instanceof Proposition) {
			sb.append("network["+indexMap.get((Proposition)c)+"]");
		} else if (c instanceof And) {
			for (Component in: c.getInputs()) {
				sb.append(generateAssignment(in, indexMap));
				sb.append(" & ");
			}
			sb.delete(sb.length()-3, sb.length());
		} else if (c instanceof Or) {
			for (Component in: c.getInputs()) {
				sb.append(generateAssignment(in, indexMap));
				sb.append(" | ");
			}
			sb.delete(sb.length()-3, sb.length());
		} else if (c instanceof Transition) {
			sb.append(generateAssignment(c.getSingleInput(), indexMap));
		} else if (c instanceof Constant) {
			if (c.getValue())
				sb.append("1");
			else
				sb.append("0");
		} else if (c instanceof Not) {
			sb.append(generateAssignment(c.getSingleInput(), indexMap));
		}

		sb.append(")");

		if ( c instanceof Not) {
			sb.append(" )");
		}


		return sb;

	}

	private static String generateUpdateLine(Proposition p, PropNet net, Map<Proposition,Integer> indexMap) {
		StringBuilder sb = new StringBuilder();

		if (p.getInputs().isEmpty())
			return sb.toString();

		sb.append("\t\tnetwork["+indexMap.get(p)+"] = ");

		StringBuilder assignment = generateAssignment(p.getSingleInput(),indexMap);
		sb.append(assignment);
		sb.append(";");

		return sb.toString();
	}

	private static void generateUpdateFn(BufferedWriter output, PropNet p, Map<Proposition,Integer> indexMap) throws IOException {

		writeLine(output, UPDATE_DECL);

		for (Proposition prop: p.getOrdering()) {
			writeLine(output, generateUpdateLine(prop, p,indexMap));
		}

		writeLine(output, "\t}");
	}

	private static void generateMappingDocument(BufferedWriter output, Map<Proposition,Integer> indexMap) throws IOException {
		writeLine(output, "\t/******************** PROPOSITION MAPPING:******************************");

		for (Map.Entry<Proposition,Integer> e: indexMap.entrySet()) {
			writeLine(output,"\t* "+e.getValue()+" -> "+e.getKey().getName().toString());
		}
		writeLine(output, "\t************************************************************************/");
	}

	private static File generateSourceFile(String gameName, PropNet p, Map<Proposition, Integer> indexMap) throws IOException {

		String className = gameName+CLASS_POSTFIX;

		File propNetSrcFile = new File(SRC_DIR+className+".java");
		BufferedWriter output = null;
		try {
			output = new BufferedWriter(new FileWriter(propNetSrcFile));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		writeLine(output, FILE_HEADER);
		writeLine(output, PACKAGE_HEADER);
		writeLine(output, "");

		writeLine(output, INCLUDES);
		writeLine(output, "");

		writeLine(output, "public final class "+className+" extends CompiledPropNet {");

		writeLine(output, "\tpublic "+className+"(int size, Map<Proposition,Integer> indexMap) {");
		writeLine(output, "\t\tsuper(size,indexMap);");
		writeLine(output, "\t}");

		generateMappingDocument(output, indexMap);

		generateUpdateFn(output, p, indexMap);

		writeLine(output, "}");

		output.close();
		return propNetSrcFile;
	}

	private static HashMap<Proposition, Integer> getPropIndices(PropNet net) {
		HashMap<Proposition, Integer> indexMap = new HashMap<Proposition, Integer>();

		Set<Proposition> props = net.getPropositions();
		Iterator<Proposition> iter = props.iterator();

		int i = 0;
		while (iter.hasNext()) {
			indexMap.put(iter.next(), i);
			i++;
		}

		return indexMap;
	}

	private static PropNet compile(String gameName, PropNet p) {

		File srcFile = null;
		HashMap<Proposition, Integer> indexMap = getPropIndices(p);
		try {
			srcFile = generateSourceFile(gameName, p, indexMap);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}


		try {

			/** Compilation Requirements *********************************************************************************************/
			DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
			JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
			StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);

			// This sets up the class path that the compiler will use.
			// I've added the .jar file that contains the DoStuff interface within in it...
			List<String> optionList = new ArrayList<String>();
			optionList.add("-classpath");
			optionList.add(System.getProperty("java.class.path"));
			optionList.add("-d");
			optionList.add(DEST_DIR);

			Iterable<? extends JavaFileObject> compilationUnit
			= fileManager.getJavaFileObjectsFromFiles(Arrays.asList(srcFile));
			JavaCompiler.CompilationTask task = compiler.getTask(
					null,
					fileManager,
					diagnostics,
					optionList,
					null,
					compilationUnit);
			/********************************************************************************************* Compilation Requirements **/
			if (task.call()) {
				/** Load and execute *************************************************************************************************/
				System.out.println("PropNet for "+gameName+" compiled successfully");
				// Create a new custom class loader, pointing to the directory that contains the compiled
				// classes, this should point to the top of the package structure!
				URLClassLoader classLoader = new URLClassLoader(new URL[]{new File("./").toURI().toURL()});
				// Load the class from the classloader by name....
				Class<?> loadedClass = classLoader.loadClass(PACKAGE+"."+gameName+CLASS_POSTFIX);
				// Create a new instance...
				Object obj = null;
				try {
					obj = loadedClass.getDeclaredConstructor(int.class,Map.class).newInstance(p.getPropositions().size(), indexMap);
				} catch (NoSuchMethodException | InvocationTargetException e) {
					e.printStackTrace();
				}

				if (obj instanceof CompiledPropNet) {
					System.out.println("Instantiated compiled propNet successfully");
				} else {
					System.out.println("Unable to instantiate compiled propNet");
				}
				// Santity check
				//if (obj instanceof DoStuff) {
				// Cast to the DoStuff interface
				//  DoStuff stuffToDo = (DoStuff)obj;
				// Run it baby
				//stuffToDo.doStuff();
				//}
				/************************************************************************************************* Load and execute **/
			} else {
				for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
					System.out.format("Error on line %d in %s: %s%n",
							diagnostic.getLineNumber(),
							diagnostic.getSource().toUri(),
							diagnostic.getMessage(null));
				}
			}
			fileManager.close();
		} catch (IOException | ClassNotFoundException | InstantiationException | IllegalAccessException exp) {
			exp.printStackTrace();
		}


		return null;
	}

}
