package org.jshint;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import net.htmlparser.jericho.Element;
import net.htmlparser.jericho.HTMLElementName;
import net.htmlparser.jericho.Source;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.jshint.reporters.CheckstyleReporter;
import org.jshint.reporters.DefaultReporter;
import org.jshint.reporters.JSHintReporter;
import org.jshint.reporters.JslintXmlReporter;
import org.jshint.reporters.NonErrorReporter;
import org.jshint.reporters.ReporterResult;
import org.jshint.reporters.UnixReporter;
import org.jshint.utils.JSHintUtils;
import org.jshint.utils.Minimatch;
import com.github.jshaptic.js4j.ContainerFactory;
import com.github.jshaptic.js4j.JsonParser;
import com.github.jshaptic.js4j.JsonParserException;
import com.github.jshaptic.js4j.Lodash;
import com.github.jshaptic.js4j.UniversalContainer;
import com.github.jshaptic.js4j.ValueCustomizer;

public class Cli
{
	private static final CommandLineParser cli = new DefaultParser();
	private static final org.apache.commons.cli.Options OPTIONS = new org.apache.commons.cli.Options();
	static
	{
		OPTIONS.addOption(Option.builder("c")
										.longOpt("config")
										.hasArg()
										.desc("Custom configuration file")
										.argName("file")
										.build());
		
		OPTIONS.addOption(Option.builder()
										.longOpt("reporter")
										.hasArg()
										.desc("Custom reporter (<PATH>|jslint|checkstyle|unix)")
										.argName("name")
										.build());
		
		OPTIONS.addOption(Option.builder()
										.longOpt("prereq")
										.hasArg()
										.desc("Comma-separated list of prerequisites (paths). E.g. files which include definitions of global variables used throughout your project")
										.argName("files")
										.build());
		
		OPTIONS.addOption(Option.builder()
										.longOpt("exclude")
										.hasArg()
										.desc("Exclude files matching the given filename pattern (same as .jshintignore)")
										.argName("pattern")
										.build());
		
		OPTIONS.addOption(Option.builder()
										.longOpt("exclude-path")
										.hasArg()
										.desc("Pass in a custom jshintignore file path")
										.argName("path")
										.build());
		
		OPTIONS.addOption(Option.builder()
										.longOpt("filename")
										.hasArg()
										.desc("Pass in a filename when using STDIN to emulate config lookup for that file name")
										.argName("file")
										.build());
		
		OPTIONS.addOption(Option.builder()
										.longOpt("verbose")
									   	.desc("Show message codes")
									   	.build());
		
		OPTIONS.addOption(Option.builder()
										.longOpt("show-non-errors")
										.desc("Show additional data generated by jshint")
										.build());
		
		OPTIONS.addOption(Option.builder("e")
										.longOpt("extra-ext")
										.hasArg()
										.desc("Comma-separated list of file extensions to use (default is .js)")
										.argName("extensions")
										.build());
		
		OPTIONS.addOption(Option.builder()
										.longOpt("extract")
										.hasArg()
										.desc("Extract inline scripts contained in HTML (auto|always|never, default to never)")
										.argName("when")
										.build());
		
		OPTIONS.addOption(Option.builder("v")
										.longOpt("version")
										.desc("Display the current version")
										.build());
		
		OPTIONS.addOption(Option.builder("h")
										.longOpt("help")
										.desc("Display help and usage details")
										.build());
		
		// Deprecated options.
		
		OPTIONS.addOption(Option.builder()
										.longOpt("jslint-reporter")
										.desc(deprecated("Use a jslint compatible reporter", "--reporter=jslint"))
										.build());
		
		OPTIONS.addOption(Option.builder()
										.longOpt("checkstyle-reporter")
										.desc(deprecated("Use a CheckStyle compatible XML reporter", "--reporter=checkstyle"))
										.build());
	}
	
	// Storage for memoized results from find file
	// Should prevent lots of directory traversal &
	// lookups when liniting an entire project
	private Map<String, String> findFileResults = new HashMap<String, String>();
	
	/**
	 * Returns the same text but with a deprecation notice.
	 * Useful for options descriptions.
	 *
	 * @param text
	 * @param alt (optional) Alternative command to include in the
	 *        deprecation notice.
	 *
	 * @returns
	 */
	private static String deprecated(String text, String alt)
	{
		if (StringUtils.isEmpty(alt))
		{
			return text + " (DEPRECATED)";
		}
		return text + " (DEPRECATED, use " + alt + " instead)";
	}
	
	/**
	 * Tries to find a configuration file in either project directory
	 * or in the home directory. Configuration files are named
	 * '.jshintrc'.
	 *
	 * @param file path to the file to be linted
	 * @returns a path to the config file
	 */
	private String findConfig(String file)
	{
		String dir = JSHintUtils.path.dirname(JSHintUtils.path.resolve(file));
		String envs = getHomeDir();
		String proj = findFile(".jshintrc", dir);
		String home;
		
		if (StringUtils.isNotEmpty(proj))
		{
			return proj;
		}
		else if (StringUtils.isNotEmpty(envs))
		{
			home = JSHintUtils.path.normalize(JSHintUtils.path.join(envs, ".jshintrc"));
			
			if (JSHintUtils.shell.exists(home))
				return home;
		}
		
		return null;
	}
	
	private String getHomeDir()
	{
		return System.getProperty("user.home");
	}
	
	/**
	 * Tries to find JSHint configuration within a package.json file
	 * (if any). It search in the current directory and then goes up
	 * all the way to the root just like findFile.
	 *
	 * @param   file path to the file to be linted
	 * @returns config object
	 */
	private UniversalContainer loadNpmConfig(String file)
	{
		String dir = JSHintUtils.path.dirname(JSHintUtils.path.resolve(file));
		String fp  = findFile("package.json", dir);
		
		if (StringUtils.isEmpty(fp))
		{
			return ContainerFactory.nullContainer();
		}
		
		try
		{
			return JsonParser.parse(JSHintUtils.shell.cat(fp)).get("jshintConfig");
		}
		catch (Exception e)
		{
			return ContainerFactory.nullContainer();
		}
	}
	
	private JSHintReporter loadReporter(ReporterType fp)
	{
		switch (fp)
		{
		case CHECKSTYLE:
			return new CheckstyleReporter();
		case JSLINT_XML:
			return new JslintXmlReporter();
		case NON_ERROR:
			return new NonErrorReporter();
		case UNIX:
			return new UnixReporter();
		default:
			return null;
		}
	}
	
	private JSHintReporter loadReporter(String reporterJavaFile)
	{
		if (StringUtils.isNotEmpty(reporterJavaFile))
		{
			int compileResult = 0;
			
			if (!reporterJavaFile.endsWith(".class"))
			{
				reporterJavaFile = reporterJavaFile.endsWith(".java") ? reporterJavaFile : reporterJavaFile + ".java";
				JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
				compileResult = compiler.run(null, null, System.err, reporterJavaFile);
			}
			
			if (compileResult == 0)
			{
				reporterJavaFile = reporterJavaFile.replaceAll("\\.java$", "");
				Path p = Paths.get(reporterJavaFile).toAbsolutePath();
				Path classPath = p.getParent();
				String className = p.getName(p.getNameCount()-1).toString();
				
				try
				{
					URLClassLoader cl = new URLClassLoader(new URL[]{classPath.toUri().toURL()});
					Class<?> reporterClass = cl.loadClass(className);
					cl.close();
					if (ArrayUtils.contains(reporterClass.getInterfaces(), JSHintReporter.class));
					{
						return (JSHintReporter)reporterClass.newInstance();
					}
				}
				catch (ClassNotFoundException e)
				{
					return null;
				}
				catch (InstantiationException e)
				{
					return null;
				}
				catch (IllegalAccessException e)
				{
					return null;
				}
				catch (MalformedURLException e)
				{
					return null;
				}
				catch (IOException e)
				{
					return null;
				}
			}
		}
		
		return null;
	}
	
	/**
	 * Searches for a file with a specified name starting with
	 * 'dir' and going all the way up either until it finds the file
	 * or hits the root.
	 *
	 * @param name filename to search for (e.g. .jshintrc)
	 * @param dir  directory to start search from (default:
	 *             current working directory)
	 *
	 * @returns normalized filename
	 */
	private String findFile(String name, String cwd)
	{
		cwd = StringUtils.defaultIfEmpty(cwd, JSHintUtils.shell.cwd());
		
		String filename = JSHintUtils.path.normalize(JSHintUtils.path.join(cwd, name));
		
		if (findFileResults.containsKey(filename))
		{
			return findFileResults.get(filename);
		}
		
		String parent = JSHintUtils.path.resolve(cwd, "../");
		
		if (JSHintUtils.shell.exists(filename))
		{
			findFileResults.put(filename, filename);
			return filename;
		}
		
		if (cwd.equals(parent))
		{
			findFileResults.put(filename, null);
			return null;
		}
		
		return findFile(name, parent);
	}
	
	/**
	 * Loads a list of files that have to be skipped. JSHint assumes that
	 * the list is located in a file called '.jshintignore'.
	 * 
	 * @return a list of files to ignore.
	 * @throws IOException if ignore files cannot be read from file system
	 */
	private List<String> loadIgnores(String exclude, String excludePath, String cwd) throws IOException
	{
		String file = StringUtils.defaultString(findFile(StringUtils.defaultIfEmpty(excludePath, ".jshintignore"), cwd));
		
		if (StringUtils.isEmpty(file) && StringUtils.isEmpty(exclude))
		{
			return new ArrayList<String>();
		}
		
		String[] ls = StringUtils.isNotEmpty(file) ? JSHintUtils.shell.cat(file).split("\\n", -1) : new String[]{};
		ls = ArrayUtils.addAll(StringUtils.defaultString(exclude).split(","), ls);
		
		List<String> lines = new ArrayList<String>();
		
		for (String line : ls)
		{
			if (line.trim().isEmpty()) continue;
			
			if (line.startsWith("!"))
			{
				lines.add("!" + JSHintUtils.path.resolve(JSHintUtils.path.dirname(file), line.substring(1).trim()));
				continue;
			}
			
			lines.add(JSHintUtils.path.resolve(JSHintUtils.path.dirname(file), line.trim()));
		}
		
		return lines;
	}
	
	/**
	 * Checks whether we should ignore a file or not.
	 *
	 * @param fp       a path to a file
	 * @param patterns a list of patterns for files to ignore
	 *
	 * @return 'true' if file should be ignored, 'false' otherwise.
	 */
	private boolean isIgnored(String fp, List<String> patterns)
	{
		for (String ip : patterns)
		{
			if (Minimatch.test(JSHintUtils.path.resolve(fp), ip, Minimatch.NO_CASE))
			{
				return true;
			}
			
			if (JSHintUtils.path.resolve(fp).equals(ip))
			{
				return true;
			}
			
			if (JSHintUtils.shell.isDirectory(fp) && Reg.test("^[^\\/\\\\]*[\\/\\\\]?$", ip) &&
					Reg.test("^" + ip + ".*", fp))
			{
				return true;
			}
			
		}
		return false;
	}
	
	/**
	 * Crude version of source maps: extract how much JavaSscript in HTML
	 * was shifted based on first JS line. For example if first js line
	 * is offset by 4 spaces, each line in this js fragment will have offset 4
	 * to restore the original column.
	 *
	 * @param code a piece of code
	 * @param when 'always' will extract the JS code, no matter what.
	 * 'never' won't do anything. 'auto' will check if the code looks like HTML
	 * before extracting it.
	 *
	 * @return extracted offsets
	 */
	private Map<Integer, Integer> extractOffsets(String code, String when)
	{
		// A JS file won't start with a less-than character, whereas a HTML file
		// should always start with that.
		if (!when.equals("always") && (!when.equals("auto") || !Reg.test("^\\s*<", code)))
			return null;
		
		Map<Integer, Integer> offsets = new HashMap<Integer, Integer>();
		
		Source parser = new Source(code);
		
		for (Element s : parser.getAllElements(HTMLElementName.SCRIPT))
		{
			if (StringUtils.isNotEmpty(s.getAttributeValue("type")) && !s.getAttributeValue("type").toLowerCase().contains("text/javascript"))
				continue;
			
			int lineCounter = s.getContent().getRowColumnVector().getRow();
			
			String[] lines = s.getContent().toString().split("\\r\\n|\\n|\\r", -1);
			
			String startOffset = null;
			for (String line : lines)
			{
				if (line.trim().isEmpty()) continue;
				startOffset = Reg.exec("^([\\s\\t]*)", line)[1];
				break;
			}
			
			for (int i = 0; i < lines.length; i++)
			{
				if (startOffset != null)
				{
					offsets.put(lineCounter, startOffset.length());
				}
				else
				{
					offsets.put(lineCounter, 0);
				}
				lineCounter++;
			}
		}
		
		return offsets;
	}
	
	/**
	 * Recursively gather all files that need to be linted,
	 * excluding those that user asked to ignore.
	 *
	 * @param fp      a path to a file or directory to lint
	 * @param files   a pointer to an array that stores a list of files
	 * @param ignores a list of patterns for files to ignore
	 * @param ext     a list of non-dot-js extensions to lint
	 */
	private void collect(String fp, List<String> files, List<String> ignores, String ext)
	{
		if (ignores != null && isIgnored(fp, ignores))
		{
			return;
		}
		
		if (!JSHintUtils.shell.exists(fp))
		{
			System.err.println("Can't open " + fp);
			return;
		}
		
		if (JSHintUtils.shell.isDirectory(fp))
		{
			for (String item : JSHintUtils.shell.ls(fp))
			{
				String itempath = JSHintUtils.path.join(fp, item);
				if (JSHintUtils.shell.isDirectory(itempath) || Reg.test(ext, itempath))
				{
					collect(itempath, files, ignores, ext);
				}
			}
			
			return;
		}
		
		files.add(fp);
	}
	
	/**
	 * Runs JSHint against provided file and saves the result
	 *
	 * @param code    code that needs to be linted
	 * @param results a pointer to an object with results
	 * @param config  an object with JSHint configuration
	 * @param data    a pointer to an object with extra data
	 * @param file    (optional) file name that is being linted
	 * @throws IOException if there is issue reading files from filesystem
	 * @throws JSHintException if <b>code</b> cannot be linted for some reason
	 */
	private void lint(String code, List<ReporterResult> results, UniversalContainer config, List<DataSummary> data, String file) throws IOException, JSHintException
	{
		LinterGlobals globals = new LinterGlobals();
		List<String> buffer = new ArrayList<String>();
		
		try
		{
			config = JsonParser.parse(JsonParser.stringify(config));
		}
		catch (JsonParserException e)
		{
			e.printStackTrace();
		}
		
		if (config.test("prereq"))
		{
			for (UniversalContainer v : config.get("prereq"))
			{
				String fp = JSHintUtils.path.join(config.asString("dirname"), v.asString());
				if (JSHintUtils.shell.exists(fp))
					buffer.add(JSHintUtils.shell.cat(fp));
			}
			config.delete("prereq");
		}
		
		if (config.test("globals"))
		{
			globals.putAll(config.get("globals"));
			config.delete("globals");
		}
		
		if (config.test("overrides"))
		{
			if (StringUtils.isNotEmpty(file))
			{
				for (String pattern : config.get("overrides").keys())
				{
					UniversalContainer options = config.get("overrides").get(pattern);
					if (Minimatch.test(JSHintUtils.path.normalize(file), pattern, Minimatch.NO_CASE | Minimatch.MATCH_BASE))
					{
						if (options.test("globals"))
						{
							globals.putAll(options.get("globals"));
							options.delete("globals");
						}
						config.extend(options);
					}
				}
			}
			
			config.delete("overrides");
		}
		
		config.delete("dirname");
		
		buffer.add(code);
		code = StringUtils.join(buffer, "\n");
		code = code.replace("^\uFEFF", ""); // Remove potential Unicode BOM.
		
		JSHint jshint = new JSHint();
		
		if (!jshint.lint(code, new LinterOptions(config), globals))
		{
			for (LinterWarning err: jshint.getErrors())
			{
				results.add(new ReporterResult(StringUtils.defaultIfEmpty(file, "stdin"), err));
			}
		}
		
		DataSummary lintData = jshint.generateSummary();
		
		if (lintData != null)
		{
			lintData.setFile(StringUtils.defaultIfEmpty(file, "stdin"));
			data.add(lintData);
		}
	}
	
	protected void error(String msg)
	{
		System.err.println(msg);
	}
	
	/**
	 * Extract JS code from a given source code. The source code my be either HTML
	 * code or JS code. In the latter case, no extraction will be done unless
	 * 'always' is given.
	 *
	 * @param code a piece of code.
	 * @param when 'always' will extract the JS code, no matter what.
	 * 'never' won't do anything. 'auto' will check if the code looks like HTML
	 * before extracting it.
	 *
	 * @return the extracted code.
	 */
	public String extract(String code, String when)
	{
		// A JS file won't start with a less-than character, whereas a HTML file
		// should always start with that.
		if (!when.equals("always") && (!when.equals("auto") || !Reg.test("^\\s*<", code)))
			return code;
		
		int startIndex = 1;
		int endIndex = 1;
		List<String> js = new ArrayList<String>();
		
		Source parser = new Source(code);
		
		for (Element s : parser.getAllElements(HTMLElementName.SCRIPT))
		{
			if (StringUtils.isNotEmpty(s.getAttributeValue("type")) && !s.getAttributeValue("type").toLowerCase().contains("text/javascript"))
				continue;
			
			String data = s.getContent().toString();
			endIndex = s.getContent().getRowColumnVector().getRow();
			
			for (int i = startIndex; i < endIndex; i++)
			{
				js.add("\n");
			}
			
			String[] lines = data.split("\\r\\n|\\n|\\r", -1);
			
			String startOffset = null;
			for (String line : lines)
			{
				if (line.trim().isEmpty()) continue;
				startOffset = Reg.exec("^([\\s\\t]*)", line)[1];
				break;
			}
			
			if (startOffset != null)
			{
				for (int i = 0; i < lines.length; i++)
				{
					lines[i] = lines[i].replaceFirst(startOffset, "");
				}
				data = StringUtils.join(lines, "\n");
			}
			
			js.add(data);
			
			startIndex = endIndex + lines.length - 1;
		}
		
		return StringUtils.join(js, "");
	}
	
	/**
	 * Shortcut that just throws ExitException with specified exit code.
	 * 
	 * @param code exit code.
	 * @throws ExitException purpose of this method is to throw ExitException.
	 */
	public void exit(int code) throws ExitException
	{
		throw new ExitException(code);
	}
	
	/**
	 * Returns a configuration file or nothing, if it can't be found.
	 * 
	 * @param fp a path to the config file.
	 * @return configuration file.
	 * @throws ExitException if config cannot be resolved due to any reason.
	 */
	public UniversalContainer getConfig(String fp) throws ExitException
	{
		UniversalContainer ret = loadNpmConfig(fp);
		return ret.test() ? ret : loadConfig(findConfig(fp));
	}
	
	/**
	 * Loads and parses a configuration file.
	 *
	 * @param fp a path to the config file.
	 * @throws ExitException if config cannot be loaded due to any reason.
	 * @return configuration object.
	 */
	public UniversalContainer loadConfig(String fp) throws ExitException
	{
		if (StringUtils.isEmpty(fp))
		{
			return ContainerFactory.createObject();
		}
		
		if (!JSHintUtils.shell.exists(fp))
		{
			error("Can't find config file: " + fp);
			exit(1);
		}
		
		try
		{
			UniversalContainer config = JsonParser.parse(JSHintUtils.shell.cat(fp));
			config.set("dirname", JSHintUtils.path.dirname(fp));
			
			if (config.test("extends"))
			{
				UniversalContainer baseConfig = loadConfig(JSHintUtils.path.resolve(config.asString("dirname"), config.asString("extends")));
				config = Lodash.merge(ContainerFactory.createObject(), new ValueCustomizer()
				{
					@Override
					public UniversalContainer customize(UniversalContainer a, UniversalContainer b)
					{
						if (a.isArray())
						{
							return a.concat(b);
						}
						return ContainerFactory.undefinedContainer();
					}
					
				}, baseConfig, config);
				config.delete("extends");
			}
			
			return config;
		}
		catch (Exception e)
		{
			error("Can't parse config file: " + fp + "\nError:" + e.getMessage());
			exit(1);
		}
		
		return null;
	}

	/**
	 * Gathers all files that need to be linted.
	 *
	 * @param opts post-processed options from main function.
	 * @return list of files.
	 * @throws IOException if files cannot be read from filesystem.
	 */
	public List<String> gather(RunOptions opts) throws IOException
	{
		List<String> files = new ArrayList<String>();
		String reg = "\\.(js" +
				(opts.extensions == null || opts.extensions.isEmpty() ? "" : "|" +
						opts.extensions.replaceAll(",", "|").replaceAll("[\\. ]", "")) + ")$";
		
		List<String> ignores = new ArrayList<String>();
		if (opts.ignores == null)
		{
			ignores = loadIgnores(null, null, opts.cwd);
		}
		else
		{
			for (String target : opts.ignores)
			{
				ignores.add(JSHintUtils.path.resolve(target));
			}
		}
		
		for (String target : opts.args)
		{
			collect(target, files, ignores, reg);
		}
		
		return files;
	}
	
	private void mergeCliPrereq(RunOptions opts, UniversalContainer config)
	{
		if (StringUtils.isNotEmpty(opts.prereq))
		{
			config.set("prereq", ContainerFactory.createArrayIfFalse(config.get("prereq")).concat(new UniversalContainer(opts.prereq.split("\\s*,\\s*"))));
		}
	}
	
	/**
	 * Gathers all files that need to be linted, lints them, sends them to
	 * a reporter and returns the overall result.
	 *
	 * @param opts post-processed options from main function.
	 * @return true if all files passed, false otherwise.
	 * @throws ExitException if code linting cannot be started.
	 * @throws IOException if required files cannot be read from filesystem.
	 * @throws JSHintException if there is issue during code linting.
	 */
	public boolean run(RunOptions opts) throws ExitException, JSHintException, IOException
	{
		List<String> files = gather(opts);
		List<ReporterResult> results = new ArrayList<ReporterResult>();
		List<DataSummary> data = new ArrayList<DataSummary>();
		
		String filename = "";
		
		// There is an if(filename) check in the lint() function called below.
		// passing a filename of undefined is the same as calling the function
		// without a filename.  If there is no opts.filename, filename remains
		// undefined and lint() is effectively called with 4 parameters.
		if (StringUtils.isNotEmpty(opts.filename))
		{
			filename = JSHintUtils.path.resolve(opts.filename);
		}
		if (opts.useStdin && opts.ignores.indexOf(filename) == -1)
		{
			String code = JSHintUtils.cli.readFromStdin();
			
			UniversalContainer config = ContainerFactory.undefinedContainerIfFalse(opts.config);
			
			if (StringUtils.isNotEmpty(filename) && !config.test())
			{
				config = getConfig(filename);
			}
			
			config = ContainerFactory.createObjectIfFalse(config);
			
			mergeCliPrereq(opts, config);
			
			lint(extract(code, opts.extract), results, config, data, filename);
		}
		else
		{
			for (String file : files)
			{
				UniversalContainer config = ContainerFactory.undefinedContainerIfFalse(opts.config);
				String code = "";
				List<ReporterResult> errors = new ArrayList<ReporterResult>();
				
				config = config.test() ? config : getConfig(file);
				
				try
				{
					code = JSHintUtils.shell.cat(file);
				}
				catch (IOException e)
				{
					error("Can't open " + file);
					exit(1);
				}
				
				mergeCliPrereq(opts, config);
				
				lint(extract(code, opts.extract), errors, config, data, file);
				
				if (errors.size() > 0)
				{
					Map<Integer, Integer> offsets = extractOffsets(code, opts.extract);
					if (offsets != null && offsets.size() > 0)
					{
						for (ReporterResult errorInfo : errors)
						{
							int line = errorInfo.getError().getLine();
							if (line >= 0 && offsets.containsKey(line) && offsets.get(line) != 0)
							{
								errorInfo.getError().shiftCharacter(offsets.get(line));
							}
						}
					}
					
					results.addAll(errors);
				}
			}
		}
		
		JSHintReporter reporter = opts.reporter != null ? opts.reporter : new DefaultReporter();
		reporter.generate(results, data, opts.verbose);
		return results.size() == 0;
	}
	
	/**
	 * Main entrance function. Parses arguments and calls #run(RunOptions) when
	 * its done.
	 *
	 * @param args list of passed arguments.
	 * @return exit code.
	 */
	public int interpret(String... args)
	{
		try
		{
			try
			{
				CommandLine options = cli.parse(OPTIONS, args);
				
				if (options.hasOption("help") || options.hasOption("version"))
				{
					for (Option o : options.getOptions())
					{
						if (o.getLongOpt().equals("version"))
						{
							Properties prop = new Properties();
							prop.load(getClass().getResourceAsStream("/package.properties"));
							System.out.println(prop.getProperty("version"));	
							exit(0);
						}
						if (o.getLongOpt().equals("help"))
						{
							HelpFormatter formatter = new HelpFormatter();
							formatter.printHelp("jshint", "", OPTIONS, "", true);
							exit(0);
						}
					}
				}
				
				// Use config file if specified
				UniversalContainer config = ContainerFactory.undefinedContainer();
				if (options.hasOption("config"))
				{
					config = loadConfig(options.getOptionValue("config"));
				}
				
				// JSLint reporter
				JSHintReporter reporter = null;
				if ((options.hasOption("reporter") && options.getOptionValue("reporter").equals("jslint")) || options.hasOption("jslint-reporter"))
				{
					reporter = loadReporter(ReporterType.JSLINT_XML);
				}
				
				// CheckStyle (XML) reporter
				else if ((options.hasOption("reporter") && options.getOptionValue("reporter").equals("checkstyle")) || options.hasOption("checkstyle-reporter"))
				{
					reporter = loadReporter(ReporterType.CHECKSTYLE);
				}
				
				// Unix reporter
				else if (options.hasOption("reporter") && options.getOptionValue("reporter").equals("unix"))
				{
					reporter = loadReporter(ReporterType.UNIX);
				}
				
				// Reporter that displays additional JSHint data
				else if (options.hasOption("show-non-errors"))
				{
					reporter = loadReporter(ReporterType.NON_ERROR);
				}
				
				// Custom reporter
				else if (options.hasOption("reporter") && StringUtils.isNotEmpty(options.getOptionValue("reporter")))
				{
					reporter = loadReporter(JSHintUtils.path.resolve(JSHintUtils.shell.cwd(), options.getOptionValue("reporter")));
					
					if (reporter == null)
					{
						error("Can't load reporter file: " + options.getOptionValue("reporter"));
						exit(1);
					}
				}
				
				boolean passed = true;
				
				passed = run(new RunOptions(
					ArrayUtils.removeElement(options.getArgs(), "-"),
					config,
					reporter,
					loadIgnores(options.getOptionValue("exclude"), options.getOptionValue("exclude-path"), null),
					options.getOptionValue("extra-ext"),
					options.getOptionValue("verbose"),
					options.getOptionValue("extract", "never"),
					options.getOptionValue("filename"),
					options.getOptionValue("prereq"),
					args.length > 0 && (args[args.length-1].equals("-") || args[args.length-1].equals("/dev/stdin"))
				));
				
				exit(passed ? 0 : 2);
			
			}
			catch (org.apache.commons.cli.ParseException | JSHintException | IOException e)
			{
				error(e.getMessage());
				exit(1);
			}
		}
		catch (ExitException e)
		{
			return e.getExitCode();
		}
		
		return 0;
	}
	
	public static void main(String[] args)
	{
		Cli cli = new Cli();
		System.exit(cli.interpret(args));
	}
	
	public static class RunOptions
	{
		private String[] args; // CLI arguments
		private UniversalContainer config; // Configuration object
		private JSHintReporter reporter; // Reporter object
		private List<String> ignores; // A list of files/dirs to ignore (defaults to .jshintignores)
		private String extensions; // A list of non-dot-js extensions to check
		private String verbose;
		private String extract;
		private String filename;
		private String prereq;
		private boolean useStdin;
		private String cwd;
		
		public RunOptions()
		{
			setArgs(null);
			setConfig(null);
			setReporter(null);
			setIgnores(null);
			setExtensions(null);
			setVerbose(null);
			setExtract(null);
			setFilename(null);
			setUseStdin(false);
			setCwd(null);
		}
		
		public RunOptions(String[] args, UniversalContainer config, JSHintReporter reporter, List<String> ignores, String extensions, String verbose, String extract, String filename, String prereq, boolean useStdin)
		{
			setArgs(args);
			setConfig(config);
			setReporter(reporter);
			setIgnores(ignores);
			setExtensions(extensions);
			setVerbose(verbose);
			setExtract(extract);
			setFilename(filename);
			setPrereq(prereq);
			setUseStdin(useStdin);
			setCwd(null);
		}
		
		public void setArgs(String[] args)
		{
			this.args = ArrayUtils.nullToEmpty(args);
		}

		public void setConfig(UniversalContainer config)
		{
			this.config = config;
		}

		public void setReporter(JSHintReporter reporter)
		{
			this.reporter = reporter;
		}

		public void setIgnores(List<String> ignores)
		{
			this.ignores = ignores != null ? ignores : new ArrayList<String>();
		}

		public void setExtensions(String extensions)
		{
			this.extensions = StringUtils.defaultString(extensions);
		}

		public void setVerbose(String verbose)
		{
			this.verbose = StringUtils.defaultString(verbose);
		}

		public void setExtract(String extract)
		{
			this.extract = StringUtils.defaultString(extract);
		}

		public void setFilename(String filename)
		{
			this.filename = StringUtils.defaultString(filename);
		}
		
		public void setPrereq(String prereq)
		{
			this.prereq = prereq;
		}

		public void setUseStdin(boolean useStdin)
		{
			this.useStdin = useStdin;
		}

		public void setCwd(String cwd)
		{
			this.cwd = StringUtils.defaultString(cwd);
		}

		public String[] getArgs()
		{
			return args;
		}

		public UniversalContainer getConfig()
		{
			return config;
		}

		public JSHintReporter getReporter()
		{
			return reporter;
		}

		public List<String> getIgnores()
		{
			return ignores;
		}

		public String getExtensions()
		{
			return extensions;
		}

		public String getVerbose()
		{
			return verbose;
		}

		public String getExtract()
		{
			return extract;
		}

		public String getFilename()
		{
			return filename;
		}
		
		public String getPrereq()
		{
			return prereq;
		}

		public boolean isUseStdin()
		{
			return useStdin;
		}

		public String getCwd()
		{
			return cwd;
		}
	}
	
	public static class ExitException extends Exception
	{
		private static final long serialVersionUID = -6680026802899600801L;
		private int exitCode = 0;
		
		public ExitException(int exitCode)
		{
			this.exitCode = exitCode;
		}
		
		public int getExitCode()
		{
			return exitCode;
		}
	}
	
	public static enum ReporterType
	{
		JSLINT_XML,
		CHECKSTYLE,
		UNIX,
		NON_ERROR
	}
}