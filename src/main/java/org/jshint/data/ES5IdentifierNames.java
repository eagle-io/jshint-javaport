package org.jshint.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

public class ES5IdentifierNames
{
	private ES5IdentifierNames() {};
	
	private static final Pattern pattern;
		
	static
	{
		try (
			InputStream in = ClassLoader.getSystemResourceAsStream("es5-identifier-names.txt");
			BufferedReader reader = new BufferedReader(new InputStreamReader(in));
		)
		{
			String str = reader.readLine();
			//String str = new String(Files.readAllBytes(Paths.get(ClassLoader.getSystemClassLoader().getResource("es5-identifier-names.txt").toURI())));
			pattern = Pattern.compile(str);
		}
		catch (IOException e)
		{
			throw new RuntimeException("Cannot load resource file!");
		}
	}
	
	public static boolean test(String value)
	{
		return pattern.matcher(StringUtils.defaultString(value)).find();
	}
}