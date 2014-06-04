package org.ggp.base.util.propnet.factory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
	private static final String SRC_DIR = "src/org/ggp/base/util/propnet/architecture/generated/";
	private static final String DEST_DIR = "bin";

	private static final String PACKAGE = "org.ggp.base.util.propnet.architecture.generated";
	private static final String PACKAGE_HEADER = "package "+PACKAGE+";";
	private static final String INCLUDES = "import org.ggp.base.util.propnet.architecture.CompiledPropNet;\n"
										 + "import org.ggp.base.util.propnet.architecture.PropNet;\n"
										 + "import org.ggp.base.util.propnet.architecture.components.Proposition;\n"
					 					 + "import java.util.Map;";

	private static final String UPDATE_DECL = "\tpublic void update() {";
	private static final String UPDATE_SEGMENT_DECL = "\tprivate void update%d() {";
	private static final String UPDATE_BASES_DECL = "\tpublic void updateBases() {";
	private static final String UPDATE_BASES_SEGMENT_DECL = "\tpublic void updateBases%d() {";
	private static final String UPDATE_BASES_SEGMENT_CALL = "\t\tupdateBases%d();";
	private static final String UPDATE_SEGMENT_CALL = "\t\tupdate%d();";

	private static final String PROPAGATE_DECL = "\tpublic void propagate(int propId) {";
	private static final String PROPAGATE_IND_DECL = "\tpublic void propagate%d() {";
	private static final String PROPAGATE_CASE = "\t\t\tcase %d: propagate%d();";

	private static final String UPDATE_SINGLE_DECL = "\tpublic void updateSingleProp(int propId) {";
	private static final String UPDATE_SINGLE_SEGMENT_DECL = "\tprivate void updateSingle%d(int propId) {";
	private static final String UPDATE_SINGLE_SEGMENT_CALL = "\t\t\tcase %d: updateSingle%d(offset); break;";

	private static final String CLASS_POSTFIX = "PropNet_%s";

	private static final int SEGMENT_SIZE = 10;
	private static final int UPDATE_SINGLE_SEGMENT_SIZE = SEGMENT_SIZE/2;

	/**
	 * Creates a PropNet for the game with the given description.
	 *
	 * @throws InterruptedException if the thread is interrupted during
	 * PropNet creation.
	 */
	public static CompiledPropNet create(String gameName, List<Gdl> description) throws InterruptedException {
		return create(gameName, description, false);
	}

	public static CompiledPropNet create(String gameName, List<Gdl> description, boolean verbose) throws InterruptedException {
		PropNet opt = OptimizingPropNetFactory.create(description, verbose);

		opt.renderToFile("MultipleTicTacToe.dot");

		CompiledPropNet cProp = compile(gameName, opt);

		return cProp;
	}

	private static void writeLine(BufferedWriter out, String line) throws IOException {
		out.write(line + "\n");
	}

	private static StringBuilder generateAssignment(Component c, Map<Proposition,Integer> indexMap) {
		StringBuilder sb = new StringBuilder();

		//sb.append("(byte)");

		if (c instanceof Not) {
			sb.append("(!");
		}

		sb.append("(");

		if (c instanceof Proposition) {
			sb.append("network["+indexMap.get((Proposition)c)+"]");
		} else if (c instanceof And) {
			for (Component in: c.getInputs()) {
				sb.append(generateAssignment(in, indexMap));
				sb.append(" && ");
			}
			sb.delete(sb.length()-3, sb.length());
		} else if (c instanceof Or) {
			for (Component in: c.getInputs()) {
				sb.append(generateAssignment(in, indexMap));
				sb.append(" || ");
			}
			sb.delete(sb.length()-3, sb.length());
		} else if (c instanceof Transition) {
			sb.append(generateAssignment(c.getSingleInput(), indexMap));
		} else if (c instanceof Constant) {
			if (c.getValue())
				sb.append("true");
			else
				sb.append("false");
		} else if (c instanceof Not) {
			sb.append(generateAssignment(c.getSingleInput(), indexMap));
		}

		sb.append(")");

		if ( c instanceof Not) {
			sb.append(" )");
		}


		return sb;

	}

	private static String generateUpdateLine(Proposition p, Map<Proposition,Integer> indexMap) {
		StringBuilder sb = new StringBuilder();

		if (p.getInputs().isEmpty())
			return sb.toString();

		sb.append("\t\tnetwork["+indexMap.get(p)+"] = ");

		StringBuilder assignment = generateAssignment(p.getSingleInput(),indexMap);
		sb.append(assignment);
		sb.append(";");

		return sb.toString();
	}

	private static int generateSegmentedUpdateFn(BufferedWriter output, PropNet p, Map<Proposition, Integer> indexMap) throws IOException {

		int i = 0;
		int numSegments = 0;
		List<Proposition> ordering = p.getOrdering();
		while (i < ordering.size()) {
			if (i % SEGMENT_SIZE == 0) {
				if (numSegments > 0)
					writeLine(output, "\t}\n");
				writeLine(output, String.format(UPDATE_SEGMENT_DECL, numSegments));
				numSegments++;
			}
			writeLine(output,generateUpdateLine(ordering.get(i),indexMap));
			i++;
		}

		writeLine(output, "\t}\n");

		return numSegments;
	}

	private static void generateUpdateFn(BufferedWriter output, PropNet p, Map<Proposition,Integer> indexMap) throws IOException {
		if (p.getOrdering().size() > SEGMENT_SIZE) {
			int numSegments = generateSegmentedUpdateFn(output, p, indexMap);
			writeLine(output, UPDATE_DECL);
			for (int i = 0; i < numSegments; i++) {
				writeLine(output,String.format(UPDATE_SEGMENT_CALL, i));
			}
			writeLine(output, "\t}");
		} else {
			writeLine(output, UPDATE_DECL);
			for (Proposition prop: p.getOrdering()) {
				writeLine(output, generateUpdateLine(prop,indexMap));
			}
			writeLine(output, "\t}");
		}
	}

	private static void generateUpdateBasesFn(BufferedWriter output, PropNet p, Map<Proposition, Integer> indexMap) throws IOException {

		int numSegments = generateSegmentedUpdateBasesFn(output,p,indexMap);

		writeLine(output, UPDATE_BASES_DECL);
		for (int i = 0; i < numSegments; i++) {
			writeLine(output,String.format(UPDATE_BASES_SEGMENT_CALL, i));
		}
		writeLine(output, "\t}");
	}

	private static int generateSegmentedUpdateBasesFn(BufferedWriter output,
			PropNet p, Map<Proposition, Integer> indexMap) throws IOException {

		int i = 0;
		int numSegments = 0;
		Collection<Proposition> ordering = p.getBasePropositions().values();

		Iterator<Proposition> iter = ordering.iterator();

		while (iter.hasNext()) {
			if (i % SEGMENT_SIZE == 0) {
				if (numSegments > 0)
					writeLine(output, "\t}\n");
				writeLine(output, String.format(UPDATE_BASES_SEGMENT_DECL, numSegments));
				numSegments++;
			}
			writeLine(output,generateUpdateLine(iter.next(),indexMap));
			i++;
		}

		writeLine(output, "\t}\n");
		return numSegments;
	}

	private static void generatePropagateIndFn(BufferedWriter output, Map<Proposition,Integer> indexMap, Proposition prop, PropNet p) throws IOException {

		writeLine(output,String.format(PROPAGATE_IND_DECL, indexMap.get(prop)));
		Queue<Component> toProcess = new LinkedList<Component>();
		List<Proposition> toUpdate = new LinkedList<Proposition>();
		Set<Component> processed = new HashSet<Component>();
		toProcess.addAll(prop.getOutputs());
		processed.addAll(prop.getOutputs());
		processed.addAll(p.getBasePropositions().values());

		while (!toProcess.isEmpty()) {
			Component c = toProcess.poll();

			if (c instanceof Proposition) {
				toUpdate.add((Proposition)c);
			}

			for (Component out : c.getOutputs()) {
				if (!processed.contains(out)) {
					toProcess.add(out);
					processed.add(out);
				}
			}

		}

		for (Proposition out  : toUpdate) {
			writeLine(output,generateUpdateLine(out,indexMap));
		}

		writeLine(output, "\t}");
	}


	private static void generatePropagateFns(BufferedWriter output, PropNet p, Map<Proposition,Integer> indexMap) throws IOException {

		int count = 0;

		for (Proposition prop : p.getBasePropositions().values()) {
			generatePropagateIndFn(output,indexMap,prop,p);
			count++;
		}

		for (Proposition prop : p.getInputPropositions().values()) {
			generatePropagateIndFn(output,indexMap,prop,p);
			count++;
		}

		writeLine(output,"");
		writeLine(output, PROPAGATE_DECL);
		writeLine(output,"\t\tswitch(propId) {");

		for (int i = 0; i < count; i++) {
			writeLine(output,String.format(PROPAGATE_CASE, i, i));
		}

		writeLine(output,"\t\t}");
		writeLine(output,"\t}");
	}

	// For debuggging
	private static void generateMappingDocument(BufferedWriter output, Map<Proposition,Integer> indexMap) throws IOException {

		Proposition [] sortedList = new Proposition[indexMap.size()];

		for (Map.Entry<Proposition,Integer> e: indexMap.entrySet()) {
			sortedList[e.getValue()] = e.getKey();
		}

		writeLine(output, "\t/******************** PROPOSITION MAPPING:******************************");
		for (int i=0; i < sortedList.length; i++) {
			writeLine(output,"\t* "+ i +" -> "+sortedList[i].getName().toString());
		}
		writeLine(output, "\t************************************************************************/");
	}

	private static int generateSegmentedUpdateSingleFn(BufferedWriter output, PropNet p, Map<Proposition, Integer> indexMap) throws IOException {

		int i = 0;
		int numSegments = 0;
		List<Proposition> ordering = p.getOrdering();
		while (i < ordering.size()) {
			if (i % UPDATE_SINGLE_SEGMENT_SIZE == 0) {
				if (numSegments > 0) {
					writeLine(output, "\t\t}");
					writeLine(output, "\t}\n");
				}
				writeLine(output, String.format(UPDATE_SINGLE_SEGMENT_DECL, numSegments));
				writeLine(output,"\t\tswitch(propId) {");
				numSegments++;
			}


			writeLine(output,"\t\tcase "+i%UPDATE_SINGLE_SEGMENT_SIZE+":");
			writeLine(output,generateUpdateLine(ordering.get(i),indexMap));
			writeLine(output,"\t\tbreak;");
			i++;
		}

		writeLine(output, "\t\t}");
		writeLine(output, "\t}\n");

		return numSegments;
	}


	private static File generateSourceFile(String className, PropNet p, Map<Proposition, Integer> indexMap) throws IOException {

		File propNetSrcFile = new File(SRC_DIR+className+".java");

		if (!propNetSrcFile.getParentFile().exists() && !propNetSrcFile.getParentFile().mkdirs()) {
			System.err.println("Could not create directory for generated source files: "+SRC_DIR);
			return null;
		}

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
		writeLine(output, "");

		writeLine(output, "\tpublic "+className+"(int size, Map<Proposition,Integer> indexMap, PropNet p) {");
		writeLine(output, "\t\tsuper(size,indexMap, p);");
		writeLine(output, "\t}");
		writeLine(output, "");

		generateMappingDocument(output, indexMap);

		generateUpdateFn(output, p, indexMap);
		writeLine(output, "");

		generateUpdateBasesFn(output, p, indexMap);
		writeLine(output, "");

		//generatePropagateFns(output, p, indexMap);
		generateUpdateSingleFns(output,p,indexMap);
		writeLine(output, "}");

		output.close();
		return propNetSrcFile;
	}

	private static void generateUpdateSingleFns(BufferedWriter output,
			PropNet p, Map<Proposition, Integer> indexMap) throws IOException {

		int numSegments = generateSegmentedUpdateSingleFn(output,p,indexMap);

		writeLine(output, UPDATE_SINGLE_DECL);
		writeLine(output, "\t\tint segment = propId / "+UPDATE_SINGLE_SEGMENT_SIZE+";");
		writeLine(output, "\t\tint offset  = propId % "+UPDATE_SINGLE_SEGMENT_SIZE+";");
		writeLine(output,"\t\tswitch (segment) {");
		for (int i = 0; i < numSegments; i++) {
			writeLine(output, String.format(UPDATE_SINGLE_SEGMENT_CALL,i,i));
		}
		writeLine(output, "\t\t}");
		writeLine(output, "\t}\n");

	}

	private static HashMap<Proposition, Integer> getPropIndices(PropNet net) {
		HashMap<Proposition, Integer> indexMap = new HashMap<Proposition, Integer>();

		HashSet<Proposition> baseProps = new HashSet<Proposition>(net.getBasePropositions().values());
		HashSet<Proposition> inputProps = new HashSet<Proposition>(net.getInputPropositions().values());

		Set<Proposition> props = net.getPropositions();
		Iterator<Proposition> iter = baseProps.iterator();

		int i = 0;

		// Put base propositions at the beginning of the vector
		while (iter.hasNext()) {
			indexMap.put(iter.next(), i);
			i++;
		}

		iter = inputProps.iterator();

		while (iter.hasNext()) {
			indexMap.put(iter.next(),i);
			i++;
		}

		iter = props.iterator();
		while (iter.hasNext()) {
			Proposition p = iter.next();

			if (!baseProps.contains(p) && !inputProps.contains(p)) {
				indexMap.put(p, i);
				i++;
			}
		}

		return indexMap;
	}

	private static CompiledPropNet compile(String gameName, PropNet p) {

		File srcFile = null;
		HashMap<Proposition, Integer> indexMap = getPropIndices(p);
		String classPostfix = String.format(CLASS_POSTFIX, new SimpleDateFormat("yyyyMMdd_HHmmss").format(Calendar.getInstance().getTime()));
		String className = gameName+classPostfix;

		try {
			srcFile = generateSourceFile(className, p, indexMap);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}

		if (srcFile == null)
			return null;

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
				Class<?> loadedClass = classLoader.loadClass(PACKAGE+"."+className);
				// Create a new instance...
				Object obj = null;
				try {
					obj = loadedClass.getDeclaredConstructor(int.class,Map.class, PropNet.class).newInstance(p.getPropositions().size(), indexMap, p);
				} catch (NoSuchMethodException | InvocationTargetException e) {
					e.printStackTrace();
				}

				classLoader.close();
				classLoader = null;

				if (obj instanceof CompiledPropNet) {
					System.out.println("Instantiated compiled propNet successfully");
					return (CompiledPropNet)obj;
				} else {
					System.out.println("Unable to instantiate compiled propNet");
				}
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
