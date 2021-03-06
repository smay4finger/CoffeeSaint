/* Released under GPL2, (C) 2009-2011 by folkert@vanheusden.com */
import com.vanheusden.nagios.*;

import javax.net.ssl.X509TrustManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.HostnameVerifier;
import java.security.SecureRandom;
import javax.net.ssl.SSLSession;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.WindowEvent;
import java.awt.geom.Rectangle2D;
import java.awt.font.FontRenderContext;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.Random;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.RepaintManager;

public class CoffeeSaint
{
	static String versionNr = "v4.9-b1";
	static String version = "CoffeeSaint " + versionNr + ", (C) 2009-2011 by folkert@vanheusden.com";

	final public static Log log = new Log(250);

	volatile static Config config;

	Predictor predictor;
	long lastPredictorDump = 0;
	static Semaphore predictorSemaphore = new Semaphore(1);
	//
	static PerformanceData performanceData;
	long lastPerformanceDump = 0;
	static Semaphore performanceDataSemaphore = new Semaphore(1);
	//
	static DataSource latencyData = new DataSource("LatencyData");
	long lastLatencyDump = 0;
	static Semaphore latencyDataSemaphore = new Semaphore(1);
	//
	static int currentImageFile = 0;
	static Semaphore imageSemaphore = new Semaphore(1);
	//
	static Statistics statistics = new Statistics();
	static Semaphore statisticsSemaphore = new Semaphore(1);
	//
	static Random random = new Random(System.currentTimeMillis());
	//
	private static TrustManager[] trustManagers;

	public CoffeeSaint() throws Exception
	{
		if (config.getBrainFileName() != null)
		{
			predictor = new Predictor(config.getSleepTime());

			try
			{
				System.out.println("Loading brain from " + config.getBrainFileName());
				predictor.restoreBrainFromFile(config.getBrainFileName());
			}
			catch(FileNotFoundException e)
			{
				log.add("File " + config.getBrainFileName() + " not found, continuing(!) anyway");
			}
		}

		if (config.getPerformanceDataFileName() != null)
		{
			System.out.println("Reloading performance data from " + config.getPerformanceDataFileName());

			try
			{
				performanceData = new PerformanceData(config.getPerformanceDataFileName());
			}
			catch(FileNotFoundException fnfe)
			{
				log.add("Performance data file " + config.getPerformanceDataFileName() + " does not exist. Continuing.");
				performanceData = new PerformanceData();
			}
		}
		else
		{
			performanceData = new PerformanceData();
		}

		latencyData.setUnit("ms");
		if (config.getLatencyFile() != null)
		{
			System.out.println("Reloading latencydata from " + config.getLatencyFile());
			try
			{
				latencyData = new DataSource("LatencyData", config.getLatencyFile());
			}
			catch(FileNotFoundException fnfe)
			{
				log.add("File " + config.getLatencyFile() + " not found, continuing(!) anyway");
			}
		}
	}

	public static List<String> convertStringArrayToList(String [] array)
	{
		List<String> list = new ArrayList<String>();

		for(int index=0; index<array.length; index++)
			list.add(array[index]);

		return list;
	}

	protected java.util.List<DataSource> getPerformanceData(String host, String service)
	{
		String entity = host;
		if (service != null)
			entity += " | " + service;
		// System.out.println("sparkline entity: " + entity);

		PerformanceDataPerElement element = performanceData.get(entity);
		if (element != null)
			return element.getAllDataSources();

		return null;
	}

	BufferedImage getSparkLine(Host host, Service service, int width, int height, boolean withMeta)
	{
		return getSparkLine(host.getHostName(), service != null ?service.getServiceName() : null, null, width, height, withMeta);
	}

	BufferedImage getSparkLine(Host host, Service service, String selectedDataSourceName, int width, int height, boolean withMeta)
	{
		return getSparkLine(host.getHostName(), service.getServiceName(), selectedDataSourceName, width, height, withMeta);
	}

	BufferedImage getSparkLine(String host, String service, int width, int height, boolean withMeta)
	{
		return getSparkLine(host, service, null, width, height, withMeta);
	}

	int calcY(double value, double min, double max, double avg, double sd, double scale, int height)
	{
		double scaledValue;

		if (config.getSparklineGraphMode() == SparklineGraphMode.AVG_SD)
			scaledValue = (value - (avg - sd)) * scale;
		else
			scaledValue = (value - min) * scale;
		scaledValue = Math.max(scaledValue, 0.0);
		scaledValue = Math.min(scaledValue, height - 1.0);

		return (height - 1) - (int)scaledValue;
	}

	void dottedLine(BufferedImage output, int y, int width, int color)
	{
		for(int x=0; x<width; x+=4)
			output.setRGB(x, y, color);
	}

	BufferedImage getSparkLine(String host, String service, String selectedDataSourceName, int width, int height, boolean withMeta)
	{
		performanceDataSemaphore.acquireUninterruptibly();

		java.util.List<DataSource> dataSourcesIn = getPerformanceData(host, service);
		java.util.List<DataSource> dataSources = new ArrayList<DataSource>();
		if (dataSourcesIn == null)
		{
			performanceDataSemaphore.release();
			return null;
		}

		for(DataSource dataSource : dataSourcesIn)
		{
			if (selectedDataSourceName != null && dataSource.getDataSourceName().equals(selectedDataSourceName) == false)
				continue;

			dataSources.add(dataSource);
		}

		BufferedImage output = drawGraph(dataSources, width, height, withMeta);

		performanceDataSemaphore.release();

		return output;
	}

	public DataInfo getLatencyStats()
	{
		DataInfo dataInfo;

		latencyDataSemaphore.acquireUninterruptibly();
		dataInfo = latencyData.getStats();
		latencyDataSemaphore.release();

		return dataInfo;
	}

	BufferedImage getLatencyGraph(int width, int height, boolean withMeta)
	{
		latencyDataSemaphore.acquireUninterruptibly();

		java.util.List<DataSource> dataSources = new ArrayList<DataSource>();
		dataSources.add(latencyData);

		BufferedImage output = drawGraph(dataSources, width, height, withMeta);

		latencyDataSemaphore.release();

		return output;
	}

	BufferedImage drawGraph(java.util.List<DataSource> dataSources, int width, int height, boolean withMeta)
	{
		BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics g = output.getGraphics();

		g.setColor(Color.WHITE);
		g.fillRect(0, 0, width, height);

//		Color [] colors = { new Color(0x59432E), Color.RED, Color.BLACK, Color.GREEN, Color.BLUE };
//		int colorIndex = 0;

		for(DataSource dataSource : dataSources)
		{
			DataInfo stats = dataSource.getStats();
			double min = stats.getMin();
			double max = stats.getMax();
			double avg = stats.getAvg();
			double sd  = stats.getSd();
			double scale = 1.0, half, quarter, quarter3;
			if (config.getSparklineGraphMode() == SparklineGraphMode.AVG_SD)
			{
				double two_sd = 2.0 * sd;
				if (two_sd != 0.0)
					scale = height / two_sd;
				half = avg;
				quarter = avg - (sd / 2.0);
				quarter3 = avg + (sd / 2.0);
			}
			else
			{
				double diff = max - min;
				if (diff != 0.0)
					scale = height / diff;
				half = (min + max) / 2.0;
				quarter = (max - min) / 4.0 + min;
				quarter3 = ((max - min) / 4.0) * 3.0 + min;
			}
			java.util.List<Double> values = dataSource.getValues();
			int px = -1, py = -1;

			g.setColor(config.getGraphColor());
//			g.setColor(colors[colorIndex++]);
//			if (colorIndex == colors.length)
//				colorIndex = 0;

			double sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumX2 = 0.0;
			Integer firstOffset = null;
			int nElements = 0;
			for(int offset=0; offset<width; offset++)
			{
				int dataOffset = offset + (values.size() - width) - 1;
				if (dataOffset >= 0)
				{
					if (firstOffset == null)
						firstOffset = offset;
					nElements++;
					double value = values.get(dataOffset);
					double scaledValue = calcY(value, min, max, avg, sd, scale, height);
					int y = (int)scaledValue;
					int x = offset;

					if (px == -1 || py == -1)
						output.setRGB(x, y, g.getColor().getRGB());
					else
						g.drawLine(px, py, x, y);

					px = x;
					py = y;

					// least squares line
					int timeStamp = dataOffset;
					sumX += timeStamp;
					sumY += values.get(dataOffset);
					sumXY += timeStamp * values.get(dataOffset);
					sumX2 += Math.pow(timeStamp, 2.0);
				}
			}

			if (withMeta)
			{
				int y = calcY(half, min, max, avg, sd, scale, height);
				g.setColor(Color.RED);
				dottedLine(output, y, width, g.getColor().getRGB());
				g.drawLine(0, y, 2, y);
				g.setFont(new Font(config.getFontName(), Font.BOLD, (int)(height / 7.5)));
				g.drawString(String.format("%g", half), 5, y + ((Graphics2D)g).getFontMetrics().getAscent() / 2);

				y = calcY(quarter, min, max, avg, sd, scale, height);
				dottedLine(output, y, width, g.getColor().getRGB());
				g.drawLine(0, y, 2, y);
				g.drawString(String.format("%g", quarter), 5, y + ((Graphics2D)g).getFontMetrics().getAscent() / 2);

				y = calcY(quarter3, min, max, avg, sd, scale, height);
				dottedLine(output, y, width, g.getColor().getRGB());
				g.drawLine(0, y, 2, y);
				g.drawString(String.format("%g", quarter3), 5, y + ((Graphics2D)g).getFontMetrics().getAscent() / 2);

				int usedNElements = nElements;
				double dummy = (usedNElements * sumX2 - sumX * sumX);
				if (usedNElements != 0 && dummy != 0.0)
				{
					double b = (usedNElements * sumXY - sumX * sumY) / dummy;
					double a = (sumY / usedNElements) - b * (sumX / usedNElements);
					for(int offset=firstOffset; offset<width; offset+=2)
					{
						int dataOffset = offset + (values.size() - width) - 1;
						double value = a + (double)dataOffset * b;
						y = calcY(value, min, max, avg, sd, scale, height);

						output.setRGB(offset, Math.max(0, Math.min(y, height - 1)), Color.GREEN.getRGB());
					}
				}
			}
		}

		return output;
	}

	public void collectPerformanceData(JavNag javNag) throws Exception
	{
		performanceDataSemaphore.acquireUninterruptibly();
		try
		{
			for(Host currentHost : javNag.getListOfHosts())
			{
				String hostPerformanceData = currentHost.getParameter("performance_data");
				String lastHostCheck = currentHost.getParameter("last_check");
				if (hostPerformanceData != null && hostPerformanceData.trim().equals("") == false)
					performanceData.add(currentHost.getHostName(), hostPerformanceData, lastHostCheck);

				for(Service currentService : currentHost.getServices())
				{
					String servicePerformanceData = currentService.getParameter("performance_data");
					String lastServiceCheck = currentService.getParameter("last_check");
					if (servicePerformanceData != null && servicePerformanceData.trim().equals("") == false)
						performanceData.add(currentHost.getHostName() + " | " + currentService.getServiceName(), servicePerformanceData, lastServiceCheck);
				}
			}
		}
		finally
		{
			performanceDataSemaphore.release();
		}

		if ((System.currentTimeMillis() - lastPerformanceDump)  > 1800000)
		{
			dumpPerformanceData();

			lastPerformanceDump = System.currentTimeMillis();
		}
	}

	public void dumpLatencyData() throws Exception
	{
		if (config.getLatencyFile() != null)
		{
			System.out.println("Dumping performance data to " + config.getLatencyFile());
			latencyDataSemaphore.acquireUninterruptibly();
			try {
			latencyData.dump(config.getLatencyFile());
			}
			catch(Exception e) {
				throw e;
			}
			finally {
			latencyDataSemaphore.release();
		}
	}
	}

	public void collectLatencyData(JavNag javNag) throws Exception
	{
		latencyDataSemaphore.acquireUninterruptibly();
		Double latency = javNag.getAvgCheckLatency();
		if (latency != null)
			latencyData.add(latency);
		latencyDataSemaphore.release();

		if ((System.currentTimeMillis() - lastLatencyDump) > 1800000)
		{
			dumpLatencyData();

			lastLatencyDump = System.currentTimeMillis();
		}
	}

	public boolean havePerformanceData(String host, String service)
	{
		performanceDataSemaphore.acquireUninterruptibly();
		boolean haveData = performanceData.get(host, service) != null;
		performanceDataSemaphore.release();
		return haveData;
	}

	public void dumpPerformanceData() throws Exception
	{
		performanceDataSemaphore.acquireUninterruptibly();

		try {
			if (config.getPerformanceDataFileName() != null) {
				System.out.println("Dumping performance data to " + config.getPerformanceDataFileName());
				performanceData.dump(config.getPerformanceDataFileName());
			}
		}
		finally {
			performanceDataSemaphore.release();
		}
	}

	public void cleanUp()
	{
		System.runFinalization();
		System.gc();
	}

	static public String getVersion()
	{
		return version;
	}

	static public String getVersionNr()
	{
		return versionNr;
	}

	public static void showException(Exception e)
	{
		java.util.List<String> exception = new ArrayList<String>();

		exception.add("Exception: " + e);
		exception.add("Details: " + e.getMessage());
		exception.add("Stack-trace:");
		for(StackTraceElement ste: e.getStackTrace())
		{
			exception.add(" " + ste.getClassName() + ", "
					+ ste.getFileName() + ", "
					+ ste.getLineNumber() + ", "
					+ ste.getMethodName() + ", "
					+ (ste.isNativeMethod() ? "is native method" : "NOT a native method"));
		}

		for(String line : exception)
			log.add(line);

		try
		{
			BufferedWriter out = new BufferedWriter(new FileWriter("exceptions.log", true));

                	String ts = new SimpleDateFormat("E yyyy.MM.dd  hh:mm:ss a zzz").format(Calendar.getInstance().getTime());
			out.write(ts, 0, ts.length());
			out.newLine();

			for(String line : exception)
			{
				out.write(line, 0, line.length());
				out.newLine();
			}

			out.close();
		}
		catch(Exception ne)
		{
			log.add("Exception during exception-file-write: " + ne);
		}
	}

	String make2Digit(String in)
	{
		String newStr = "00" + in;

		return newStr.substring(newStr.length() - 2);
	}

	public String hostState(String state)
	{
		if (state.equals("0")) /* UP = OK */
			return "OK";

		if (state.equals("1"))
			return "DOWN";

		if (state.equals("2"))
			return "UNREACHABLE";

		if (state.equals("3"))
			return "PENDING";

		return "?";
	}

	public String serviceState(String state)
	{
		if (state.equals("0")) /* UP = OK */
			return "OK";

		if (state.equals("1"))
			return "WARNING";

		if (state.equals("2"))
			return "CRITICAL";

		return "?";

	}

	public String stringTsToDate(String ts)
	{
		long seconds = Long.valueOf(ts);

		Calendar then = Calendar.getInstance();
		then.setTimeInMillis(seconds * 1000L);

		return "" + then.get(Calendar.YEAR) + "/" + then.get(Calendar.MONTH) + "/" + then.get(Calendar.DAY_OF_MONTH) + " " + make2Digit("" + then.get(Calendar.HOUR_OF_DAY)) + ":" + make2Digit("" + then.get(Calendar.MINUTE)) + ":" + make2Digit("" + then.get(Calendar.SECOND));
	}

	public String durationToString(long howLongInSecs)
	{
		String out = "";

		if (howLongInSecs >= 86400)
			out += "" + (howLongInSecs / 86400) + "d ";

		out += "" + make2Digit("" + ((howLongInSecs / 3600) % 24)) + ":" + make2Digit("" + ((howLongInSecs / 60) % 60)) + ":" + make2Digit("" + (howLongInSecs % 60));

		return out;
	}

	public String execWithPars(Problem problem, String file)
	{
		String line = null;

		try
		{
			Service service = null;
			Host host = null;
			if (problem != null) {
				host = problem.getHost();
				service = problem.getService();
			}

			String pluginOutput = "";
			if (service != null)
				pluginOutput = service.getParameter("plugin_output");
			else if (host != null)
				pluginOutput = host.getParameter("plugin_output");

			String [] args = { file, problem != null ? problem.getHost().getHostName() : "", service != null ? service.getServiceName() : "", problem != null ? problem.getCurrent_state() : "", pluginOutput };

			System.out.println("Invoking " + file);
			Process p = Runtime.getRuntime().exec(args);
			p.waitFor();
			BufferedReader output = new BufferedReader(new InputStreamReader(p.getInputStream()));
			line = output.readLine();

			if (line == null || line.equals("")) {
				BufferedReader errStreamReader  = new BufferedReader(new InputStreamReader(p.getErrorStream()));
				line = errStreamReader.readLine();
				errStreamReader.close();
			}
			System.out.println(file + " returned: " + line);

			output.close();

			p.destroy();
		}
		catch(Exception e)
		{
			line = e.toString();
			showException(e);
		}

		return line;
	}

	public static Object [] splitStringWithFontstyleEscapes(String s) {
		int lastIndex = 0;
		int len = s.length();
		boolean fBold = false, fItalic = false, fUnderline = false, fStrikethrough = false;
		boolean stop = false;
		String [] strs = new String[len];
		Boolean [] bold = new Boolean[len];
		Boolean [] italic = new Boolean[len];
		Boolean [] underline = new Boolean[len];
		Boolean [] strikethrough = new Boolean[len];
		Boolean [] stops = new Boolean[len];
		int count = 0;
		while(lastIndex < len) {
			boolean last = false;
			int index = s.indexOf('\\', lastIndex);
			if (index == -1) {
				last = true;
				index = len;
			}
			String before = s.substring(lastIndex, index);
			if ((index - lastIndex) > 0) {
				strs[count] = before;
				bold[count] = fBold;
				italic[count] = fItalic;
				underline[count] = fUnderline;
				strikethrough[count] = fStrikethrough;
				stops[count] = stop;
				count++;
				stop = false;
			}
			if (last)
				break;

			if (index < len - 1) {
				Character c = s.charAt(index + 1);
				if (c == 'B')
					fBold = !fBold;
				else if (c == 'I')
					fItalic = !fItalic;
				else if (c == 'U')
					fUnderline = !fUnderline;
				else if (c == 'S')
					fStrikethrough = !fStrikethrough;
				else if (c == 'T')
					stop = true;
			}

			lastIndex = index + 2;
		}
		// FIXME put unterminated last one (from index

		String [] strsOut = new String[count];
		Boolean [] boldOut = new Boolean[count];
		Boolean [] italicOut = new Boolean[count];
		Boolean [] underlineOut = new Boolean[count];
		Boolean [] ssOut = new Boolean[count];
		Boolean [] stopsOut = new Boolean[count];
		System.arraycopy(strs, 0, strsOut, 0, count);
		System.arraycopy(bold, 0, boldOut, 0, count);
		System.arraycopy(italic, 0, italicOut, 0, count);
		System.arraycopy(underline, 0, underlineOut, 0, count);
		System.arraycopy(strikethrough, 0, ssOut, 0, count);
		System.arraycopy(stops, 0, stopsOut, 0, count);

		return new Object [] { strsOut, boldOut, italicOut, underlineOut, ssOut, stopsOut };
	}

	double convertField(String in, JavNag javNag, Totals totals, Calendar rightNow, Problem problem, boolean haveNotifiedProblems, String cmd) {
		double aValue = 0.0;
		String [] aParts = in.split(":");
		if (aParts.length == 2) {
			if (aParts[0].equals("SERVICEFIELD")) {
				Service s = problem.getService();
				if (s != null)
					aValue = Double.valueOf(s.getParameter(aParts[1]));
			}
			else if (aParts[0].equals("HOSTFIELD")) {
				aValue = Double.valueOf(problem.getHost().getParameter(aParts[1]));
			}
		}
		else {
			char c = aParts[0].charAt(0);
			if (Character.isDigit(c))
				aValue = Double.valueOf(aParts[0]);
			else if (aParts[0].length() > 1) {
				Object [] result = processStringEscapes(javNag, totals, rightNow, problem, haveNotifiedProblems, aParts[0].substring(1), false);
				aValue = Double.valueOf((String)result[0]);
			}
		}

		return aValue;
	}

	public Object [] processStringEscapes(JavNag javNag, Totals totals, Calendar rightNow, Problem problem, boolean haveNotifiedProblems, String cmd, boolean recurse)
	{
		long now = System.currentTimeMillis() / 1000;
		String pars = null;
		String [] parts = cmd.split("\\^");
		cmd = parts[0];
		if (parts.length > 1)
			pars = parts[1];

		if ((cmd.equals("EQUAL") || cmd.equals("LESS") || cmd.equals("LESSOREQUAL") || cmd.equals("BIGGER") || cmd.equals("BIGGEROREQUAL")) && recurse == true) {
			double aValue = convertField(parts[1], javNag, totals, rightNow, problem, haveNotifiedProblems, cmd);
			double bValue = convertField(parts[2], javNag, totals, rightNow, problem, haveNotifiedProblems, cmd);
			boolean result = false;
			if (cmd.equals("LESS"))
				result = aValue < bValue;
			else if (cmd.equals("LESSOREQUAL"))
				result = aValue <= bValue;
			else if (cmd.equals("BIGGER"))
				result = aValue > bValue;
			else if (cmd.equals("BIGGEROREQUAL"))
				result = aValue >= bValue;
			else if (cmd.equals("EQUAL"))
				result = aValue == bValue;
			else
				return new Object [] { cmd + " is not understood", true };

			if (!result)
				return new Object [] { "", false };

			String out = parts[3];
			return processStringWithEscapes(out, javNag, rightNow, problem, haveNotifiedProblems, false);
		}

		if (cmd.equals("STATE") && recurse == true)
		{
			if (haveNotifiedProblems)
				return processStringWithEscapes(config.getStateProblemsText(), javNag, rightNow, problem, haveNotifiedProblems, false);

			return processStringWithEscapes(config.getNoProblemsText(), javNag, rightNow, problem, haveNotifiedProblems, false);
		}

		if (cmd.equals("EXEC") && pars != null)
			return new Object [] { execWithPars(problem, pars), false };

		if (cmd.equals("PERCENT"))
			return new Object [] { "%", false };

		if (cmd.equals("AT"))
			return new Object [] { "@", false };

		if (cmd.equals("FIELD") && pars != null) {
			String [] fields = pars.split("|");
			// field|service|host
			// field|host
			// field
			if (fields.length == 1) { // field
				if (problem.getService() != null)
					return new Object [] { problem.getService().getParameter(fields[0]), false };
				else if (problem.getHost() != null)
					return new Object [] { problem.getHost().getParameter(fields[0]), false };
			}
			else if (fields.length == 2) { // field|host
				javNag.getField(fields[1], fields[0]);
			}
			else if (fields.length == 3) { // field|service|host
				javNag.getField(fields[2], fields[1], fields[0]);
			}
		}

		if (cmd.equals("FIELDHOST") && pars != null && pars.length() > 0)
		{
			String data = problem.getHost().getParameter(pars);
			if (data != null)
				return new Object [] { data, false };
		}

		if (cmd.equals("FIELDSERVICE") && pars != null && pars.length() > 0)
		{
			String data = problem.getService().getParameter(pars);
			if (data != null)
				return new Object [] { data, false };
		}

		if (cmd.equals("FIELDBOOLEANHOST") && pars != null && pars.length() > 0)
		{
			String data = problem.getHost().getParameter(pars);
			if (data != null)
				return new Object [] { data.equals("1") ? "yes" : "no", false };
		}

		if (cmd.equals("FIELDBOOLEANSERVICE") && pars != null && pars.length() > 0)
		{
			String data = problem.getService().getParameter(pars);
			if (data != null)
				return new Object [] { data.equals("1") ? "yes" : "no", false };
		}

		if (cmd.equals("FIELDDATEHOST") && pars != null && pars.length() > 0)
		{
			String data = problem.getHost().getParameter(pars);
			if (data != null)
				return new Object [] { stringTsToDate(data), false };
		}

		if (cmd.equals("FIELDDATESERVICE") && pars != null && pars.length() > 0)
		{
			String data = problem.getService().getParameter(pars);
			if (data != null)
				return new Object [] { stringTsToDate(data), false };
		}

		if (cmd.equals("CRITICAL"))
			return new Object [] { "" + totals.getNCritical(), false };
		if (cmd.equals("WARNING"))
			return new Object [] { "" + totals.getNWarning(), false };
		if (cmd.equals("OK"))
			return new Object [] { "" + totals.getNOk(), false };

		if (cmd.equals("NACKED"))
			return new Object [] { "" + totals.getNAcked(), false };
		if (cmd.equals("NFLAPPING"))
			return new Object [] { "" + totals.getNFlapping(), false };

		if (cmd.equals("UP"))
			return new Object [] { "" + totals.getNUp(), false };
		if (cmd.equals("DOWN"))
			return new Object [] { "" + totals.getNDown(), false };
		if (cmd.equals("UNREACHABLE"))
			return new Object [] { "" + totals.getNUnreachable(), false };
		if (cmd.equals("PENDING"))
			return new Object [] { "" + totals.getNPending(), false };

		if (cmd.equals("TOTALISSUES"))
			return new Object [] { "" + (totals.getNCritical() + totals.getNWarning() + totals.getNDown() + totals.getNUnreachable()), false };

		if (predictor != null && cmd.equals("PREDICT"))
		{
			Double count = predictProblemCount(rightNow);
			if (count == null)
				return new Object [] { "?", false };
			String countStr = "" + count;
			int dot = countStr.indexOf(".");
			if (dot != -1)
				countStr = countStr.substring(0, dot + 2);
			return new Object [] { countStr, false };
		}

		if (cmd.equals("CHECKLATENCY"))
		{
			Double latency = javNag.getAvgCheckLatency();
			if (latency != null)
				return new Object [] { "" + String.format("%.3f", latency), false };
		}

		if (cmd.equals("HISTORICAL"))
			return new Object [] { "" + predictor.getHistorical(rightNow), false };

		if (cmd.equals("H"))
			return new Object [] { make2Digit("" + rightNow.get(Calendar.HOUR_OF_DAY)), false };
		if (cmd.equals("M"))
			return new Object [] { make2Digit("" + rightNow.get(Calendar.MINUTE)), false };

		if (cmd.equals("HOSTNAME") && problem != null && problem.getHost() != null)
			return new Object [] { problem.getHost().getHostName(), false };

		if (cmd.equals("SERVICENAME") && problem != null && problem.getService() != null)
			return new Object [] { problem.getService().getServiceName(), false };

		if (cmd.equals("SERVERNAME"))
			return new Object [] { problem.getHost().getNagiosSource(), false };

		if (cmd.equals("HOSTSTATE") && problem != null && problem.getHost() != null)
			return new Object [] { hostState(problem.getHost().getParameter("current_state")), false };

		if (cmd.equals("SERVICESTATE") && problem != null && problem.getService() != null)
			return new Object [] { serviceState(problem.getService().getParameter("current_state")), false };

		if (cmd.equals("HOSTDURATION"))
			return new Object [] { "" + durationToString(System.currentTimeMillis() / 1000 - Long.valueOf(problem.getHost().getParameter("last_state_change"))), false };
		if (cmd.equals("SERVICEDURATION"))
			return new Object [] { "" + durationToString(System.currentTimeMillis() / 1000 - Long.valueOf(problem.getService().getParameter("last_state_change"))), false };

		if (cmd.equals("HOSTSINCE") && problem != null && problem.getHost() != null)
			return new Object [] { stringTsToDate(problem.getHost().getParameter("last_state_change")), false };

		if (cmd.equals("HOSTSINCETS") && problem != null && problem.getHost() != null) {
			Host current = problem.getHost();
			String current_state = current.getParameter("current_state");
			String field = null;
			if (current_state.equals("0"))
				field = "last_time_up";
			else if (current_state.equals("1"))
				field = "last_time_down";
			else if (current_state.equals("2"))
				field = "last_time_unreachable";
			// System.out.println("======== " + field + " " + current.getParameter(field));
			if (field != null)
				return new Object [] { "" + (now - Long.valueOf(current.getParameter(field))), false };
		}

		if (cmd.equals("SERVICESINCE") && problem != null && problem.getService() != null)
			return new Object [] { stringTsToDate(problem.getService().getParameter("last_state_change")), false };

		if (cmd.equals("SERVICESINCETS") && problem != null && problem.getService() != null)
			return new Object [] { "" + (now - Long.valueOf(problem.getService().getParameter("last_state_change"))), false };

		if (cmd.equals("HOSTFLAPPING") && problem != null && problem.getHost() != null)
			return new Object [] { problem.getHost().getParameter("is_flapping").equals("1") ? "FLAPPING" : "", false };

		if (cmd.equals("SERVICEFLAPPING") && problem != null && problem.getService() != null)
			return new Object [] { problem.getService().getParameter("is_flapping").equals("1") ? "FLAPPING" : "", false };

		if (cmd.equals("OUTPUT") && problem != null)
		{
			String output;
			if (problem.getService() != null)
				output = problem.getService().getParameter("plugin_output");
			else
				output = problem.getHost().getParameter("plugin_output");
			if (output != null)
				return new Object [] { output, false };
			return new Object [] { "?", false };
		}

		if (cmd.equals("FLASH"))
			return new Object [] { "", true };

		return new Object [] { "?" + cmd + "?", false };
	}

	public Object [] processStringWithEscapes(String in, JavNag javNag, Calendar rightNow, Problem problem, boolean haveNotifiedProblems, boolean recurse)
	{
		final Totals totals = javNag.calculateStatistics();
		log.add("" + totals.getNHosts() + " hosts, " + totals.getNServices() + " services");
		boolean loadingCmd = false, atTerminator = false, hadSplitter = false;
		String cmd = "", output = "";
		boolean flash = false;

		if (in == null)
			return new Object [] { "", false };

		for(int index=0; index<in.length(); index++) {
			if (loadingCmd) {
				char currentChar = in.charAt(index);

				if (atTerminator) {
					if (currentChar == '@') {
						System.out.println("CMD: " + cmd);
						Object [] result = processStringEscapes(javNag, totals, rightNow, problem, haveNotifiedProblems, cmd, recurse);
						output += (String)result[0];
						flash |= (Boolean)result[1];

						cmd = "";
						atTerminator = loadingCmd = false;
						hadSplitter = false;
					}
					else {
						cmd += in.charAt(index);
					}
				}
				else if ((currentChar >= 'A' && currentChar <= 'Z') || (currentChar >= 'a' && currentChar <= 'z') ||
					currentChar == '^' || ((currentChar == '/' || currentChar == '_' || currentChar == '.' || currentChar != '\\') && hadSplitter == true))
				{
					cmd += in.charAt(index);
				}
				else if (currentChar == '^') {
					cmd += in.charAt(index);
					hadSplitter = true;
				}
				else {
					Object [] result = processStringEscapes(javNag, totals, rightNow, problem, haveNotifiedProblems, cmd, recurse);
					output += (String)result[0];
					flash |= (Boolean)result[1];

					cmd = "";
					loadingCmd = false;
					hadSplitter = false;

					if (currentChar == '%')
						loadingCmd = true;
					else if (currentChar == '@') {
						loadingCmd = true;
						atTerminator = true;
					}
					else
						output += in.charAt(index);
				}
			}
			else {
				if (in.charAt(index) == '%')
					loadingCmd = true;
				else if (in.charAt(index) == '@') {
					loadingCmd = true;
					atTerminator = true;
				}
				else
					output += in.charAt(index);
			}
		}

		if (cmd.equals("") == false) {
			Object [] result = processStringEscapes(javNag, totals, rightNow, problem, haveNotifiedProblems, cmd, recurse);
			output += (String)result[0];
			flash |= (Boolean)result[1];
		}

		return new Object [] { output, flash };
	}

	public String getScreenHeader(JavNag javNag, Calendar rightNow, boolean haveNotifiedProblems)
	{
		if (config.getNagiosDataSources().size() == 0)
			return "No Nagios servers selected!";
		else
			return (String)(processStringWithEscapes(config.getHeader(), javNag, rightNow, null, haveNotifiedProblems, true)[0]);
	}

	public Color stateToColor(String state, boolean hard)
	{
		if (state.equals("0") == true)
			return config.getBackgroundColorOkStatus();
		else if (state.equals("1") == true) {
			if (hard)
				return config.getWarningBgColor();
			return config.getWarningBgColorSoft();
		}
		else if (state.equals("2") == true) {
			if (hard)
				return config.getCriticalBgColor();
			return config.getCriticalBgColorSoft();
		}
		else if (state.equals("3") == true) // UNKNOWN STATE
			return config.getNagiosUnknownBgColor();
		else if (state.equals("254") == true) // no color at all
			return null;
		else if (state.equals("255") == true) // background color
			return config.getBackgroundColor();

		log.add("Unknown state: " + state);
		return Color.ORANGE;
	}

	public static void drawLoadStatus(Gui gui, int windowWidth, Graphics g, String message)
	{
		if (config.getVerbose() && gui != null && g != null)
			gui.prepareRow(g, windowWidth, 0, message, 0, "0", true, config.getBackgroundColor(), 1.0f, null, false, false, false, -1);
	}

	public ImageLoadingParameters startLoadingImages(Gui gui, int windowWidth, Graphics g) throws Exception {
		ImageLoadingParameters ilp = new ImageLoadingParameters();
		ilp.imageUrls = config.getImageUrls();

		int nImages = ilp.imageUrls.size();
		if (nImages == 0)
			return null;

		int loadNImages = config.getCamRows() * config.getCamCols();

		ilp.il = null;
		ilp.imageUrlTypes = config.getImageUrlTypes();
		ilp.indexes = new int[Math.min(nImages, loadNImages)];

		System.out.println("LOADIMGS " + nImages + " " + loadNImages);

		imageSemaphore.acquireUninterruptibly(); // lock around 'currentImageFile'
		String workingOn = null;
		try {
		if (config.getRandomWebcam())
		{
			for(int nr=0; nr<ilp.indexes.length; nr++)
			{
				boolean found;
				do
				{
					found = false;
					ilp.indexes[nr] = random.nextInt(nImages);
					for(int searchIndex=0; searchIndex<nr; searchIndex++)
					{
						if (ilp.indexes[searchIndex] == ilp.indexes[nr])
						{
							found = true;
							break;
						}
					}
				}
				while(found);
			}
		}
		else
		{
			for(int nr=0; nr<ilp.indexes.length; nr++)
			{
				ilp.indexes[nr] = currentImageFile++;
				if (currentImageFile == nImages)
					currentImageFile = 0;
			}
		}
		}
		catch(Exception e) {
			throw e;
		}
		finally {
		imageSemaphore.release();
		}

		int to = config.getWebcamTimeout() * 1000;
		if (to < 1)
			to = config.getSleepTime() * 1000;
		ilp.il = new ImageLoader[ilp.indexes.length];
		try {
		for(int nr=0; nr<ilp.indexes.length; nr++)
		{
				workingOn = ilp.imageUrls.get(ilp.indexes[nr]);
				log.add("Load image(1) " + workingOn);
				drawLoadStatus(gui, windowWidth, g, "Start load img " + workingOn);

			if (ilp.imageUrlTypes.get(ilp.indexes[nr]) == ImageUrlType.HTTP_MJPEG)
					ilp.il[nr] = new MJPEGLoader(workingOn, to);
			else
					ilp.il[nr] = new ImageLoader(workingOn, to);
			}
		}
		catch(Exception e) {
			throw new Exception("" + e + ": " + workingOn);
		}

		return ilp;
	}

	public ImageParameters [] loadImage(ImageLoadingParameters ilp, Gui gui, int windowWidth, Graphics g) throws Exception
	{
		if (ilp == null)
			return null;

		int loadNImages = ilp.il.length;
		ImageParameters [] result = new ImageParameters[ilp.indexes.length];

		for(int nr=0; nr<loadNImages; nr++)
		{
			String loadImage = ilp.imageUrls.get(ilp.indexes[nr]);
			Image image = null;
			drawLoadStatus(gui, windowWidth, g, "Load image " + loadImage);

			image = ilp.il[nr].getImage();

			if (image != null) {
				int imgWidth = image.getWidth(null);
				int imgHeight = image.getHeight(null);

				result[nr] = new ImageParameters(image, loadImage, imgWidth, imgHeight);
			}
		}

		return result;
	}

	public static BufferedImage createBufferedImage(Image image)
	{
		BufferedImage bufferedImage = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
		Graphics g = bufferedImage.createGraphics();

		g.drawImage(image, 0, 0, null);

		return bufferedImage;
	}

	public Double predictProblemCount(Calendar rightNow)
	{
		Calendar future = Calendar.getInstance();
		future.add(Calendar.SECOND, config.getSleepTime());

		Double value = predictor.predict(rightNow, future);
		if (value != null)
			value = Math.ceil(value * 10.0) / 10.0;

		log.add("Prediction value: " + value);

		return value;
	}

	public Color predictWithColor(Calendar rightNow)
	{
		Color bgColor = config.getBackgroundColorOkStatus();

		if (predictor != null)
		{
			Double value = predictProblemCount(rightNow);
			if (value != null && value != 0.0)
			{
				log.add("Expecting " + value + " problems after next interval");
				int red = 100 + (int)(value * (100.0 / (double)config.getNRows()));
				if (red < 0)
					red = 0;
				if (red > 200)
					red = 200;
				bgColor = new Color(red, 255, 0);
			}
		}

		return bgColor;
	}

	public void dumpPredictorBrainToFile() throws Exception
	{
		if (predictor != null && config.getBrainFileName() != null)
		{
			log.add("Dumping brain to " + config.getBrainFileName());

			predictor.dumpBrainToFile(config.getBrainFileName());
		}
	}

	public void learnProblems(Calendar rightNow, int nProblems) throws Exception
	{
		predictor.learn(rightNow, nProblems);

		if ((System.currentTimeMillis() - lastPredictorDump)  > 1800000)
		{
			dumpPredictorBrainToFile();

			lastPredictorDump = System.currentTimeMillis();
		}
	}

	public static Object [] loadNagiosData(Gui gui, int windowWidth, Graphics g) throws Exception
	{
		String exception = null;

		JavNag javNag = new JavNag();
		javNag.setUserAgent(version);
		javNag.setSocketTimeout(config.getSleepTime() * 1000);

		long startLoadTs = System.currentTimeMillis();

		int prevNHosts = -1;
		for(NagiosDataSource dataSource : config.getNagiosDataSources())
		{
			String logStr = "Loading data from: ", source = null;
			try {
				if (dataSource.getType() == NagiosDataSourceType.TCP)
				{
					source = dataSource.getHost() + " " + dataSource.getPort();
					logStr += source;
					drawLoadStatus(gui, windowWidth, g, "Load Nagios " + source);
					javNag.loadNagiosData(dataSource.getHost(), dataSource.getPort(), dataSource.getVersion(), false, dataSource.getPrettyName());
				}
				else if (dataSource.getType() == NagiosDataSourceType.ZTCP)
				{
					source = dataSource.getHost() + " " + dataSource.getPort();
					logStr += source;
					drawLoadStatus(gui, windowWidth, g, "zLoad Nagios " + source);
					System.out.println("zLoad Nagios " + source);
					javNag.loadNagiosData(dataSource.getHost(), dataSource.getPort(), dataSource.getVersion(), true, dataSource.getPrettyName());
				}
				else if (dataSource.getType() == NagiosDataSourceType.HTTP)
				{
					source = "" + dataSource.getURL();
					logStr += dataSource.getURL();
					drawLoadStatus(gui, windowWidth, g, "Load Nagios " + source);
					javNag.loadNagiosData(dataSource.getURL(), dataSource.getVersion(), dataSource.getUsername(), dataSource.getPassword(), config.getAllowHTTPCompression(), dataSource.getPrettyName());
				}
				else if (dataSource.getType() == NagiosDataSourceType.FILE)
				{
					source = dataSource.getFile();
					logStr += dataSource.getFile();
					drawLoadStatus(gui, windowWidth, g, "Load Nagios " + source);
					javNag.loadNagiosData(dataSource.getFile(), dataSource.getVersion(), dataSource.getPrettyName());
				}
				else if (dataSource.getType() == NagiosDataSourceType.LS)
				{
					source = dataSource.getHost() + " " + dataSource.getPort();
					logStr += source;
					drawLoadStatus(gui, windowWidth, g, "Load Nagios " + source);
					javNag.loadNagiosDataLiveStatus(dataSource.getHost(), dataSource.getPort(), dataSource.getPrettyName());
				}
				else
					throw new Exception("Unknown data-source type: " + dataSource.getType());

				if (javNag.getNumberOfHosts() == prevNHosts) {
					log.add(source + " did not return any hosts!");
					// FIXME on screen error or so?
				}
				prevNHosts = javNag.getNumberOfHosts();

				logStr += " - done.";
				log.add(logStr);
			}
			catch(Exception e) {
				exception = "Error loading Nagios data from " + source + ": " + e;
				log.add(exception);
			}
		}

		long endLoadTs = System.currentTimeMillis();

		double took = (double)(endLoadTs - startLoadTs) / 1000.0;
		log.add("Took " + took + "s to load status data");

		statisticsSemaphore.acquireUninterruptibly();
		statistics.addToTotalRefreshTime(took);
		statistics.addToNRefreshes(1);
		statisticsSemaphore.release();

		Object [] result = new Object[2];
		result[0] = javNag;
		result[1] = exception;

		return result;
	}

	public static java.util.List<Problem> findProblems(JavNag javNag) throws Exception
	{
		java.util.List<Problem> lessImportant = new ArrayList<Problem>();
		java.util.List<Problem> problems = new ArrayList<Problem>();

		// collect problems
		Problems.collectProblems(javNag, config.getPrioPatterns(), problems, lessImportant, config.getAlwaysNotify(), config.getAlsoAcknowledged(), config.getAlsoScheduledDowntime(), config.getAlsoSoftState(), config.getAlsoDisabledActiveChecks(), config.getShowServicesForHostWithProblems(), config.getShowFlapping(), config.getHostsFilterExclude(), config.getHostsFilterInclude(), config.getServicesFilterExclude(), config.getServicesFilterInclude(), config.getHostScheduledDowntimeShowServices(), config.getHostAcknowledgedShowServices(), config.getHostSDOrAckShowServices(), config.getDisplayUnknown(), config.getDisplayDown());
		// sort problems
		Problems.sortList(problems, config.getSortOrder(), config.getSortOrderNumeric(), config.getSortOrderReverse());
		Problems.sortList(lessImportant, config.getSortOrder(), config.getSortOrderNumeric(), config.getSortOrderReverse());
		// and combine them
		for(Problem currentLessImportant : lessImportant)
			problems.add(currentLessImportant);

		return problems;
	}

	public void learnProblemCount(int nProblems) throws Exception
	{
		if (predictor != null)
		{
			Calendar rightNow = Calendar.getInstance();
			predictorSemaphore.acquireUninterruptibly();
			try {
				learnProblems(rightNow, nProblems);
			}
			catch(Exception e) {
				throw e;
			}
			finally {
				predictorSemaphore.release();
			}
		}
	}

	public static void errorExit(String error)
	{
		System.err.println(error);
		System.exit(127);
	}

	public static String testFile(String filename)
	{
		try
		{
			FileReader fileHandle = new FileReader(filename); 
			if (fileHandle == null)
				return "Opening file returned null";
			fileHandle.close();
		}
		catch(Exception e)
		{
			return "" + e;
		}

		return null;
	}

	public static String testUrlString(String urlIn)
	{
		try
		{
			URL url = new URL(urlIn);
			if (url == null)
				return "URL(...) returned null";
		}
		catch(MalformedURLException mue)
		{
			return "" + mue;
		}

		return null;
	}

	public static String testPort(String host, int port)
	{
		try
		{
			Socket socket = new Socket(host, port);
			if (socket == null)
				return "Could not create socket: returned null";
		}
		catch(IOException ioe)
		{
			return "" + ioe;
		}

		return null;
	}

	public static String testUrl(URL url, boolean withAuthentication)
	{
		try
		{
			HttpURLConnection HTTPConnection = (HttpURLConnection)url.openConnection();
			if (HTTPConnection == null)
				return "HttpURLConnection.openConnection() returned null";

			HTTPConnection.connect();

			int responseCode = HTTPConnection.getResponseCode();
			if (responseCode < 200 || responseCode > 299)
			{
				if (!(withAuthentication && responseCode == 401))
					return "HTTP response code: " + responseCode;
			}
		}
		catch(IOException ioe)
		{
			return "" + ioe;
		}

		return null;
	}

	public static void daemonLoop(CoffeeSaint coffeeSaint, Config config) throws Exception
	{
		for(;;)
		{
			Thread.sleep(config.getSleepTime() * 1000);

			if (!config.getRunGui() && config.getPerformanceDataFileName() != null)
			{
				try
				{
					Object [] result = loadNagiosData(null, -1, null);
					JavNag javNag = (JavNag)result[0];
					coffeeSaint.collectPerformanceData(javNag);
					coffeeSaint.collectLatencyData(javNag);
				}
				finally
				{
				}

			}

			coffeeSaint.cleanUp();
		}
	}

	public static List<Monitor> getMonitors()
	{
		List<Monitor> monitors = new ArrayList<Monitor>();
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		GraphicsDevice[] gdArray = ge.getScreenDevices();
		String previous = "";

		for (int i = 0; i < gdArray.length; i++)
		{
			GraphicsDevice gd = gdArray[i];
			GraphicsConfiguration[] gcArray = gd.getConfigurations();
			for (int j = 0; j < gcArray.length; j++)
			{
				Rectangle bounds = gcArray[j].getBounds();
				String current = gcArray[j].getDevice().getIDstring();
				if (current.equals(previous) == false)
				{
					monitors.add(new Monitor(gdArray[i], gcArray[j], current, bounds));
					previous = current;
				}
			}
		}

		if (monitors.size() == 0)
			return null;

		return monitors;
	}

	public static void showAvailableScreens()
	{
		System.out.println("Available screens (for --use-screen):");

		List<Monitor> monitors = getMonitors();
		for(Monitor monitor : monitors)
		{
			Rectangle bounds = monitor.getBounds();
			System.out.println(monitor.getDeviceName() + " => " + bounds.width + "x" + bounds.height);
		}
	}

	public Monitor selectScreen(String device)
	{
		List<Monitor> monitors = getMonitors();
		for(Monitor monitor : monitors)
		{
			if (monitor.getDeviceName().equals(device))
			{
				JFrame f = new JFrame(monitor.getGraphicsDevice().getDefaultConfiguration());
				f.getContentPane().add(new Canvas(monitor.getGraphicsConfiguration()));
				Rectangle useable = monitor.getBounds();
				f.setLocation(useable.x, useable.y);
				f.setSize(useable.width, useable.height);
				monitor.setJFrame(f);
				return monitor;
			}
		}

		return null;
	}

	public Rectangle getDimensionsOverAllMonitors()
	{
		Rectangle vBounds = new Rectangle();
		List<Monitor> monitors = getMonitors();
		for(Monitor monitor : monitors)
		{
			Rectangle currentBounds = monitor.getBounds();
			vBounds = vBounds.union(currentBounds);
		}

		return vBounds;
	}

	public static void setIcon(CoffeeSaint coffeeSaint, JFrame f)
	{
		ClassLoader loader = coffeeSaint.getClass().getClassLoader();
		URL fileLocation = loader.getResource("com/vanheusden/CoffeeSaint/programIcon.png");
		Image img = Toolkit.getDefaultToolkit().getImage(fileLocation); 
		f.setIconImage(img);
	}

	public static void allowAllSSL()
	{
		javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier(){
				public boolean verify ( String hostname, SSLSession session) {
				return true;
				}
				});

		javax.net.ssl.SSLContext context=null;

		if(trustManagers == null)
		{
			trustManagers = new javax.net.ssl.TrustManager[]{new _FakeX509TrustManager()};
		}

		try
		{
			context = javax.net.ssl.SSLContext.getInstance("TLS");
			context.init(null, trustManagers, new SecureRandom());
		}
		catch (Exception e)
		{
			errorExit("Setting relaxed SSL settings failed: " + e);
		}

		javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
	}

	public static void showHelp()
	{
		System.out.println("--source type version x pretty_name Source to retrieve from");
		System.out.println("              Type can be: http, tcp, ztcp, file and ls");
		System.out.println("              http  expects an url like http://keetweej.vanheusden.com/status.dat");
		System.out.println("              http-auth expects an url like http://keetweej.vanheusden.com/status.dat and a username and a password");
		System.out.println("              tcp   expects a host and portnumber, e.g.: keetweej.vanheusden.com 33333");
		System.out.println("              ztcp  also expects a host and portnumber, e.g.: keetweej.vanheusden.com 33333");
		System.out.println("              ls    expects a livestatus host and portnumber, e.g.: keetweej.vanheusden.com 6557");
		System.out.println("              file  expects a file-name, e.g. /var/cache/nagios3/status.dat");
		System.out.println("              version selects the nagios-version. E.g. 1, 2 or 3");
		System.out.println("              pretty_name is used for the %SERVERNAME macro");
		System.out.println("              You can add as many Nagios servers as you like");
		System.out.println("              Example: --source file 3 /var/cache/nagios3/status.dat");
		System.out.println("");
		System.out.println("--allow-all-ssl  For https: do not check if the certificate is valid. You can use this with hosts that have a self-signed certificate. Please note that this needs to be the first commandline switch!");
		System.out.println("--disable-http-compression Don't use gzip/deflate compression in HTTP connection - usefull for fast links as the server has less load then");
		System.out.println("--proxy-host");
		System.out.println("--proxy-port  Proxy to use for outbound http requests.");
		System.out.println("--nrows x     Number of rows to show, must be at least 2");
		System.out.println("--min-row-height  Minimum height of the rows, skip or set to -1 to have a constant height");
		System.out.println("--interval x  Retrieve status every x seconds");
		System.out.println("--flash       Flash/blink new problems");
		System.out.println("--webcam-timeout x   Maximum time for loading 1 webcam. If not set, the Nagios status loading interval is used.");
		System.out.println("--use-host-alias Show host-alias instead of hostname");
		System.out.println("--fullscreen x Run in a fullscreen mode, e.g. without any borders (undecorated), no menu bars (fullscreen), spread over all monitors (allmonitors) or none (none)");
		System.out.println("--list-screens  To see a list of screens connected to the system on which CoffeeSaint is running");
		System.out.println("--use-screen x  Select screen 'x' to display the output on. This is usefull if you have multiple monitors connected to the system.");
		System.out.println("--problem-columns x  Split the screen in x columns so that it can display x * nrows");
		System.out.println("--flexible-n-columns Dynamically adjust number of columns (up to the maximum set with --problem-columns)");
		System.out.println("--image y x     Display image x on background. Can be a filename or an http-URL. One can have multiple files/url which will be shown roundrobin. y must be MJPEG/HTTP/HTTPS/FILE (HTTP[S]/FILE can be jpg/png)");
		System.out.println("--adapt-img   Reduce image-size to fit below the listed problems");
		System.out.println("--random-img  Randomize order of images shown");
		System.out.println("--transparency x Transparency for drawing (0.0...1.0) - only usefull with background image/webcam");
		System.out.println("--header-transparency x  Like '--transparency' but for the header.");
		System.out.println("--font x      Font to use. Default is 'Arial'");
		System.out.println("--list-fonts  List known fonts");
		System.out.println("--critical-font x  Font to use for critical problems");
		System.out.println("--warning-font x   Font to use for warning problems");
		System.out.println("--reduce-textwidth Try to fit text to the window width");
		System.out.println("--prefer x    Comma seperated list of regular expressions which tell what problems to show with priority (on top of the others)");
		System.out.println("--also-acknowledged Display acknowledged problems as well");
		System.out.println("--always-notify	Also display problems for which notifications are disabled");
		System.out.println("--also-scheduled-downtime Also display problems for which downtime has been scheduled");
		System.out.println("--also-soft-state   Also display problems that are not yet in hard state");
		System.out.println("--also-disabled-active-checks Also display problems for which active checks have been disabled");
		System.out.println("--suppress-flapping Do not show hosts that are flapping");
		System.out.println("--filter-unknown    Do not show unknown/pending state");
		System.out.println("--filter-down       Do not show hosts in down state");
		System.out.println("--show-flapping-icon Show an icon in front of problems indicating wether they're flapping or not");
		System.out.println("--show-services-for-host-with-problems");
		System.out.println("--bgcolor x   Select a background-color, used when there's something to notify about. Default is gray");
		System.out.println("--bgcolor-fade-to x If set, don't draw a solid color but fade (gradient) the --bgcolor color to this one.");
		System.out.println("--problem-row-gradient If set, don't draw a solid color bar but fade (gradient) the problem-status-color to this one.");
		System.out.println("--list-bgcolors     Show a list of available colors");
		System.out.println("--textcolor   Text color (header and such)");
		System.out.println("--warning-textcolor Text color of warning-problems");
		System.out.println("--critical-textcolor Text color of critical-problems");
		System.out.println("--sound x     Play sound when a warning/error state starts");
		System.out.println("--counter     Show counter decreasing upto the point that a refresh will happen");
		System.out.println("--exec x      Execute program when one or more errors are shown");
		System.out.println("--predict x   File to write brain-dump to (and read from)");
		System.out.println("--performance-data-filename x   File to write performance data to");
		System.out.println("--config x    Load configuration from file x. This overrides all configurationsettings set previously");
		System.out.println("--create-config x    Create new configuration file with filename x");
		System.out.println("--listen-port Port to listen for the internal webserver");
		System.out.println("--listen-adapter Network interface to listen for the internal webserver");
		System.out.println("--disable-http-fileselect Do not allow web-interface to select a file to write configuration to");
		System.out.println("--header x    String to display in header. Can contain escapes, see below");
		System.out.println("--footer x    String to display in footer. Can contain escapes, see below");
		System.out.println("--host-issue x  String defining how to format host-issues");
		System.out.println("--service-issue x  String defining how to format service-issues");
		System.out.println("--no-header   Do not display the statistics line in the upper row");
		System.out.println("--row-border  Draw a line between each row");
		System.out.println("--row-border-color Color of the row border");
		System.out.println("--graph-color Color of the graphs (e.g. performance data sparkline)");
		System.out.println("--sort-order [y] [z] x  Sort on field x. y and z can be 'numeric' and 'reverse'");
		System.out.println("              E.g. --sort-order numeric last_state_change (= default)");
		System.out.println("--cam-cols    Number of cams per row");
		System.out.println("--cam-rows    Number of rows with cams");
		System.out.println("--ignore-aspect-ratio Grow/shrink all webcams with the same factor. In case you have webcams with different dimensions");
		System.out.println("--scrolling-header  In case there's more information to put into it than what fits on the screen");
		System.out.println("--scroll-pixels-per-sec x  Number of pixels to scroll per second (default: 100)");
		System.out.println("--scroll-if-not-fitting    If problems do not fit, scroll them");
		System.out.println("--splitter \"x x x...\"    With \\T one can add tabs-stops. With --spliter you define at what positions to put the strings.");
		System.out.println("--draw-problems-service-split-line Draw a line at the split position.");
		System.out.println("--anti-alias  Anti-alias graphics");
		System.out.println("--max-quality-graphics Draw graphics with maximum quality.");
		System.out.println("--verbose     Show what it is doing");
		System.out.println("--color-bg-to-state   Background color depends on state:");
		System.out.println("--warning-bg-color x  Background color for warnings (yellow)");
		System.out.println("--critical-bg-color x Background color for criticals (red)");
		System.out.println("--nagios-unknown-bg-color x Background color for unknonws (magenta)");
		System.out.println("--hosts-filter-exclude x Comma-seperated list of hosts not to display");
		System.out.println("--hosts-filter-include x Comma-seperated list of hosts to display. Use in combination with --hosts-filter-exclude: will be invoked after the exclude.");
		System.out.println("--services-filter-exclude x Comma-seperated list of services not to display");
		System.out.println("--services-filter-include x Comma-seperated list of services to display. Use in combination with --services-filter-exclude: will be invoked after the exclude.");
		System.out.println("--sparkline-width x Adds sparklines to the listed problems. 'x' specifies the width in pixels");
		System.out.println("--sparkline-mode x (avg-sd or min-max) How to scale the sparkline graphcs");
		System.out.println("--no-problems-text Messages to display when there are no problems.");
		System.out.println("--state-problems-text Message to display when there are problems: used by %STATE escape.");
		System.out.println("--no-authentication Disable authentication in the web-interface.");
		System.out.println("--web-username      Username to use for web-interface authentication. You need to set the password as well!");
		System.out.println("--web-password      Username to use for web-interface authentication. You need to set the username as well!");
		System.out.println("--logo x            Image to put in the header-row.");
		System.out.println("--logo-position x   Where to put the logo in the header-row. Can be \"left\" or \"right\".");
		System.out.println("");
		System.out.print("Known colors:");
		config.listColors();
		System.out.println("");
		System.out.println("Escapes:");
		System.out.println("  %CRITICAL/%WARNING/%OK, %UP/%DOWN/%UNREACHABLE/%PENDING");
		System.out.println("  %TOTALISSUES              Sum of critical, warning, down and unreachable");
		System.out.println("  %STATE                    Either '--no-problems-text' or --state-problems-text'");
		System.out.println("  %H:%M       Current hour/minute");
		System.out.println("  %HOSTNAME/%SERVICENAME    host/service with problem");
		System.out.println("  %SERVERNAME               host/service with problem");
		System.out.println("  %HOSTSTATE/%SERVICESTATE  host/service state");
		System.out.println("  %HOSTSINCE/%SERVICESINCE  since when does this host/service have a problem");
		System.out.println("  %HOSTSINCETS/%SERVICESINCETS  (duration in seconds)");
		System.out.println("  %HOSTFLAPPING/%SERVICEFLAPPING  wether the state is flapping");
		System.out.println("  %PREDICT/%HISTORICAL      ");
		System.out.println("  %HOSTDURATION/%SERVICEDURATION how long has a host/service been down");
		System.out.println("  %OUTPUT                   Plugin output");
		System.out.println("  @FIELDDATEHOST^field@     Take 'field' from the host-fields (see 'Sort-fields' below) and convert it into a date-string");
		System.out.println("  @FIELDDATESERVICE^field@  Take 'field' from the service-fields (see 'Sort-fields' below) and convert it into a date-string");
		System.out.println("  @FIELDBOOLEANHOST^field@  Take 'field' from the host-fields (see 'Sort-fields' below) and interprete as yes/no");
		System.out.println("  @FIELDBOOLEANSERVICE^field@  Take 'field' from the service-fields (see 'Sort-fields' below) and interprete as yes/no");
		System.out.println("  @FIELDHOST^field@         Take 'field' from the host-fields (see 'Sort-fields' below) and display its contents");
		System.out.println("  @FIELDSERVICE^field@      Take 'field' from the service-fields (see 'Sort-fields' below) and display its contents");
		System.out.println("  @FIELD^x@                 x can be 'field|service|host' or 'field|host' or 'field'");
		System.out.println("  @EXEC^script@             Invoke script 'script' with as parameters: hostname, servicename (or empty string in case of a host failure), current state, plugin-output");
		System.out.println("                            Unix example: @EXEC^/usr/local/bin/my_script@");
		System.out.println("                            Windows example: @EXEC^c:\\programs\\myprogram.exec@");
		System.out.println("  %PERCENT			% character");
		System.out.println("  %AT                       @ character");
		System.out.println("  %CHECKLATENCY             Check latency");
		System.out.println("  %NACKED                   # acknowledged problems");
		System.out.println("  %NFLAPPING                # flapping problems");
		System.out.println(" Conditionals");
		System.out.println("  @LESS^V1^V2^string@          If v1 < v2, print string. String can contain escapes. V1 can be e.g. %HOSTSINCETS or others.");
		System.out.println("  @LESSOREQUAL^V1^V2^string@   If v1 <= v2, show string.");
		System.out.println("  @BIGGER^V1^V2^string@        If v1 > v2, show string.");
		System.out.println("  @BIGGEROREQUAL^V1^V2^string@ If v1 >= v2, show string.");
		System.out.println("  @EQUAL^V1^V2^string@         If v1 == v2, print string.");
		System.out.println("");
		System.out.println("Sort-fields:");
		config.listSortFields();
		System.out.println("");
	}

	public static void main(String[] arg)
	{
		try
		{
			statistics.setRunningSince(System.currentTimeMillis());

			System.out.println(version);
			System.out.println("");
			System.out.println("Please wait while initializing...");

			config = new Config();

			for(int loop=0; loop<arg.length; loop++)
			{
				String currentSwitch = arg[loop];

				try
				{
					if (arg[loop].equals("--create-config"))
					{
						config.writeConfig(arg[++loop]);
						config.setConfigFilename(arg[loop]);
					}
					else if (arg[loop].equals("--allow-all-ssl"))
						allowAllSSL();
					else if (arg[loop].equals("--use-screen"))
						config.setUseScreen(arg[++loop]);
					else if (arg[loop].equals("--footer"))
						config.setFooter(arg[++loop]);
					else if (arg[loop].equals("--list-screens"))
					{
						showAvailableScreens();
						System.exit(0);
					}
					else if (arg[loop].equals("--source"))
					{
						NagiosDataSource nds = null;
						NagiosVersion nv = null;
						String type = arg[++loop];
						String versionStr = arg[++loop];

						if (versionStr.equals("1"))
							nv = NagiosVersion.V1;
						else if (versionStr.equals("2"))
							nv = NagiosVersion.V2;
						else if (versionStr.equals("3"))
							nv = NagiosVersion.V3;
						else
							errorExit("Nagios version '" + versionStr + "' not known");

						if (type.equalsIgnoreCase("http") || type.equalsIgnoreCase("http-auth")) {
							boolean withAuth = type.equalsIgnoreCase("http-auth");
							String urlStr = arg[++loop];
							String resultStr = testUrlString(urlStr);
							if (resultStr != null)
								errorExit("Cannot parse url " + urlStr + ": " + resultStr);
							URL url = new URL(urlStr);
							String resultUrl = testUrl(url, withAuth);
							if (resultUrl != null)
								errorExit("Cannot use url " + url + " (" + resultUrl + ")");
							if (withAuth) {
								String username = arg[++loop];
								String password = arg[++loop];
								String pn = arg[++loop];
								nds = new NagiosDataSource(url, username, password, nv, pn);
							}
							else {
								String pn = arg[++loop];
								nds = new NagiosDataSource(url, nv, pn);
							}
						}
						else if (type.equalsIgnoreCase("file")) {
							String filename = arg[++loop];
							String pn = arg[++loop];
							String result = testFile(filename);
							if (result != null)
								errorExit("Cannot access file " + filename + " (" + result + ")");
							nds = new NagiosDataSource(filename, nv, pn);
						}
						else if (type.equalsIgnoreCase("tcp") || type.equalsIgnoreCase("ztcp") || type.equalsIgnoreCase("ls")) {
							String host = arg[++loop];
							int port;
							try {
								port = Integer.valueOf(arg[++loop]);
								String pn = arg[++loop];
								String result = testPort(host, port);
								if (result != null)
									errorExit("Cannot open socket on " + host + ":" + port + " (" + result + ")");
								if (type.equalsIgnoreCase("ls"))
									nds = new NagiosDataSource(host, port, pn);
								else
									nds = new NagiosDataSource(host, port, nv, type.equalsIgnoreCase("ztcp"), pn);
							}
							catch(NumberFormatException nfe) {
								errorExit("--source: expecting a port-number but got '" + arg[loop] + "'");
							}
						}
						else
							errorExit("Data source-type '" + type + "' not understood.");

						config.addNagiosDataSource(nds);
					}
					else if (arg[loop].equals("--sort-order")) {
						boolean reverse = false, numeric = false;

						for(;;) {
							++loop;
							if (arg[loop].equals("reverse"))
								reverse = true;
							else if (arg[loop].equals("numeric"))
								numeric = true;
							else
								break;
						}

						config.setSortOrder(arg[loop], numeric, reverse);
					}
					else if (arg[loop].equals("--no-header"))
						config.setShowHeader(false);
					else if (arg[loop].equals("--anti-alias"))
						config.setAntiAlias(true);
					else if (arg[loop].equals("--scrolling-header"))
						config.setScrollingHeader(true);
					else if (arg[loop].equals("--scroll-pixels-per-sec"))
						config.setScrollingPixelsPerSecond(Integer.valueOf(arg[++loop]));
					else if (arg[loop].equals("--fullscreen")) {
						String mode = arg[++loop];
						if (mode.equalsIgnoreCase("none"))
							config.setFullscreen(FullScreenMode.NONE);
						else if (mode.equalsIgnoreCase("undecorated"))
							config.setFullscreen(FullScreenMode.UNDECORATED);
						else if (mode.equalsIgnoreCase("fullscreen"))
							config.setFullscreen(FullScreenMode.FULLSCREEN);
						else if (mode.equalsIgnoreCase("allmonitors"))
							config.setFullscreen(FullScreenMode.ALLMONITORS);
						else
							errorExit("Fullscreen mode " + mode + " not recognized");
					}
					else if (arg[loop].equals("--header"))
						config.setHeader(arg[++loop]);
					else if (arg[loop].equals("--web-username"))
						config.setWebUsername(arg[++loop]);
					else if (arg[loop].equals("--web-password"))
						config.setWebPassword(arg[++loop]);
					else if (arg[loop].equals("--row-border"))
						config.setRowBorder(true);
					else if (arg[loop].equals("--service-issue"))
						config.setServiceIssue(arg[++loop]);
					else if (arg[loop].equals("--host-issue"))
						config.setHostIssue(arg[++loop]);
					else if (arg[loop].equals("--random-img"))
						config.setRandomWebcam(true);
					else if (arg[loop].equals("--no-gui"))
					{
						System.err.println("Don't forget to invoke the JVM with -Djava.awt.headless=true !");
						config.setRunGui(false);
					}
					else if (arg[loop].equals("--config"))
					{
						String filename = arg[++loop];
						String result = testFile(filename);
						if (result != null)
							errorExit("Cannot open configuration file " + filename + " (" + result + ")");
						config.loadConfig(filename);
						if (config.getAllowAllSSL())
							allowAllSSL();
					}
					else if (arg[loop].equals("--predict"))
						config.setBrainFileName(arg[++loop]);
					else if (arg[loop].equals("--performance-data-filename"))
						config.setPerformanceDataFileName(arg[++loop]);
					else if (arg[loop].equals("--exec"))
						config.setExec(arg[++loop]);
					else if (arg[loop].equals("--adapt-img"))
						config.setAdaptImageSize(true);
					else if (arg[loop].equals("--counter"))
						config.setCounter(true);
					else if (arg[loop].equals("--verbose"))
						config.setVerbose(true);
					else if (arg[loop].equals("--sound"))
						config.setProblemSound(arg[++loop]);
					else if (arg[loop].equals("--listen-port"))
					{
						try
						{
							config.setHTTPServerListenPort(Integer.valueOf(arg[++loop]));
						}
						catch(NumberFormatException nfe)
						{
							errorExit("--listen-port: expecting a port-number but got '" + arg[loop] + "'");
						}
					}
					else if (arg[loop].equals("--listen-adapter"))
						config.setHTTPServerListenAdapter(arg[++loop]);
					else if (arg[loop].equals("--list-bgcolors"))
					{
						config.listColors();
						System.exit(0);
					}
					else if (arg[loop].equals("--warning-bg-color"))
						config.setWarningBgColor(arg[++loop]);
					else if (arg[loop].equals("--warning-bg-color-soft"))
						config.setWarningBgColorSoft(arg[++loop]);
					else if (arg[loop].equals("--critical-bg-color"))
						config.setCriticalBgColor(arg[++loop]);
					else if (arg[loop].equals("--critical-bg-color-soft"))
						config.setCriticalBgColorSoft(arg[++loop]);
					else if (arg[loop].equals("--nagios-unknown-bg-color"))
						config.setNagiosUnknownBgColor(arg[++loop]);
					else if (arg[loop].equals("--bgcolor"))
						config.setBackgroundColor(arg[++loop]);
					else if (arg[loop].equals("--bgcolor-fade-to"))
						config.setBackgroundColorFadeTo(arg[++loop]);
					else if (arg[loop].equals("--problem-row-gradient"))
						config.setProblemRowGradient(arg[++loop]);
					else if (arg[loop].equals("--textcolor"))
						config.setTextColor(arg[++loop]);
					else if (arg[loop].equals("--nrows"))
						config.setNRows(Integer.valueOf(arg[++loop]));
					else if (arg[loop].equals("--min-row-height"))
						config.setMinRowHeight(Integer.valueOf(arg[++loop]));
					else if (arg[loop].equals("--interval"))
						config.setSleepTime(Integer.valueOf(arg[++loop]));
					else if (arg[loop].equals("--flash"))
						config.setFlash(true);
					else if (arg[loop].equals("--webcam-timeout"))
						config.setWebcamTimeout(Integer.valueOf(arg[++loop]));
					else if (arg[loop].equals("--image"))
					{
						String type = arg[++loop];
						String what = arg[++loop];
						if (what.length() > 7 && what.substring(0, 7).equalsIgnoreCase("http://"))
						{
							String result = testUrlString(what);
							if (result != null)
								errorExit("Cannot open image-url " + what + " (" + result + ")");
							URL url = new URL(what);
							result = testUrl(url, false);
							if (result != null)
								errorExit("Cannot open image-url " + what + " (" + result + ")");
						}
						else
						{
							String result = testFile(what);
							if (result != null)
								errorExit("Cannot open image-file " + what + " (" + result + ")");
						}
						config.addImageUrl(type + " " + what);
					}
					else if (arg[loop].equals("--problem-columns"))
						config.setNProblemCols(Integer.valueOf(arg[++loop]));
					else if (arg[loop].equals("--cam-rows"))
						config.setCamRows(Integer.valueOf(arg[++loop]));
					else if (arg[loop].equals("--cam-cols"))
						config.setCamCols(Integer.valueOf(arg[++loop]));
					else if (arg[loop].equals("--reduce-textwidth"))
						config.setReduceTextWidth(true);
					else if (arg[loop].equals("--max-quality-graphics"))
						config.setMaxQualityGraphics(true);
					else if (arg[loop].equals("--flexible-n-columns"))
						config.setFlexibleNColumns(true);
					else if (arg[loop].equals("--disable-http-fileselect"))
						config.setDisableHTTPFileselect();
					else if (arg[loop].equals("--prefer"))
						config.setPrefers(arg[++loop]);
					else if (arg[loop].equals("--always-notify"))
						config.setAlwaysNotify(true);
					else if (arg[loop].equals("--suppress-flapping"))
						config.setShowFlapping(false);
					else if (arg[loop].equals("--show-flapping-icon"))
						config.setShowFlappingIcon(true);
					else if (arg[loop].equals("--also-acknowledged"))
						config.setAlsoAcknowledged(true);
					else if (arg[loop].equals("--font"))
						config.setFontName(arg[++loop]);
					else if (arg[loop].equals("--list-fonts"))
					{
						config.listFonts();
						System.exit(0);
					}
					else if (arg[loop].equals("--no-network-change"))
						config.setNoNetworkChange(true);
					else if (arg[loop].equals("--critical-font"))
						config.setCriticalFontName(arg[++loop]);
					else if (arg[loop].equals("--warning-font"))
						config.setWarningFontName(arg[++loop]);
					else if (arg[loop].equals("--warning-textcolor"))
						config.setWarningTextColor(arg[++loop]);
					else if (arg[loop].equals("--critical-textcolor"))
						config.setCriticalTextColor(arg[++loop]);
					else if (arg[loop].equals("--row-border-color"))
						config.setRowBorderColor(arg[++loop]);
					else if (arg[loop].equals("--graph-color"))
						config.setGraphColor(arg[++loop]);
					else if (arg[loop].equals("--latency-file"))
						config.setLatencyFile(arg[++loop]);
					else if (arg[loop].equals("--ignore-aspect-ratio"))
						config.setKeepAspectRatio(false);
					else if (arg[loop].equals("--also-scheduled-downtime"))
						config.setAlsoScheduledDowntime(true);
					else if (arg[loop].equals("--show-services-for-host-with-problems"))
						config.setShowServicesForHostWithProblems(true);
					else if (arg[loop].equals("--also-soft-state"))
						config.setAlsoSoftState(true);
					else if (arg[loop].equals("--also-disabled-active-checks"))
						config.setAlsoDisabledActiveChecks(true);
					else if (arg[loop].equals("--disable-http-compression"))
						config.setAllowHTTPCompression(false);
					else if (arg[loop].equals("--transparency"))
						config.setTransparency(Float.valueOf(arg[++loop]));
					else if (arg[loop].equals("--max-check-age"))
						config.setMaxCheckAge(Long.valueOf(arg[++loop]));
					else if (arg[loop].equals("--header-transparency"))
						config.setHeaderTransparency(Float.valueOf(arg[++loop]));
					else if (arg[loop].equals("--hosts-filter-exclude"))
						config.setHostsFilterExclude(arg[++loop]);
					else if (arg[loop].equals("--hosts-filter-include"))
						config.setHostsFilterInclude(arg[++loop]);
					else if (arg[loop].equals("--services-filter-exclude"))
						config.setServicesFilterExclude(arg[++loop]);
					else if (arg[loop].equals("--services-filter-include"))
						config.setServicesFilterInclude(arg[++loop]);
					else if (arg[loop].equals("--scroll-splitter") || arg[loop].equals("--splitter"))
						config.setLineScrollSplitter(arg[++loop].trim());
					else if (arg[loop].equals("--draw-problems-service-split-line"))
						config.setDrawProblemServiceSplitLine(true);
					else if (arg[loop].equals("--sparkline-width"))
						config.setSparkLineWidth(Integer.valueOf(arg[++loop]));
					else if (arg[loop].equals("--scroll-if-not-fitting"))
						config.setScrollIfNotFit(true);
					else if (arg[loop].equals("--counter-position"))
						config.setCounterPosition(arg[++loop]);
					else if (arg[loop].equals("--no-problems-text"))
						config.setNoProblemsText(arg[++loop]);
					else if (arg[loop].equals("--state-problems-text"))
						config.setStateProblemsText(arg[++loop]);
					else if (arg[loop].equals("--no-problems-text-position"))
						config.setNoProblemsTextPosition(arg[++loop]);
					else if (arg[loop].equals("--no-authentication"))
						config.setAuthentication(false);
					else if (arg[loop].equals("--header-always-bgcolor"))
						config.setHeaderAlwaysBGColor(true);
					else if (arg[loop].equals("--color-bg-to-state"))
						config.setSetBgColorToState(true);
					else if (arg[loop].equals("--filter-unknown"))
						config.setDisplayUnknown(false);
					else if (arg[loop].equals("--filter-down"))
						config.setDisplayDown(false);
					else if (arg[loop].equals("--logo"))
					{
						String what = arg[++loop];
						if (what.length() > 7 && what.substring(0, 7).equalsIgnoreCase("http://"))
						{
							String result = testUrlString(what);
							if (result != null)
								errorExit("Cannot open logo-image url " + what + " (" + result + ")");
							URL url = new URL(what);
							result = testUrl(url, false);
							if (result != null)
								errorExit("Cannot open image-url " + what + " (" + result + ")");
						}
						else
						{
							String result = testFile(what);
							if (result != null)
								errorExit("Cannot open logo-image file " + what + " (" + result + ")");
						}
						config.setLogo(what);
					}
					else if (arg[loop].equals("--logo-position"))
						config.setLogoPosition(arg[++loop]);
					else if (arg[loop].equals("--logfile"))
						log.setLogFile(arg[++loop]);
					else if (arg[loop].equals("--proxy-host"))
						config.setProxyHost(arg[++loop]);
					else if (arg[loop].equals("--proxy-port"))
						config.setProxyPort(Integer.valueOf(arg[++loop]));
					else if (arg[loop].equals("--suppress-services-for-scheduled-host-downtime"))
						config.setHostScheduledDowntimeShowServices(false);
					else if (arg[loop].equals("--suppress-services-for-acknowledged-host-problems"))
						config.setHostAcknowledgedShowServices(false);
					else if (arg[loop].equals("--enable-double-buffering"))
						config.setDoubleBuffering(true);
					else if (arg[loop].equals("--sparkline-mode"))
					{
						String mode = arg[++loop];
						if (mode.equals("avg-sd"))
							config.setSparklineGraphMode(SparklineGraphMode.AVG_SD);
						else if (mode.equals("min-max"))
							config.setSparklineGraphMode(SparklineGraphMode.MIN_MAX);
					}
					else if (arg[loop].equals("--version") || arg[loop].equals("-version"))
					{
						System.out.println(getVersion());
						System.exit(0);
					}
					else if (arg[loop].equals("--help") || arg[loop].equals("--h"))
					{
						showHelp();
						System.exit(0);
					}
					else
					{
						showHelp();
						errorExit("Parameter " + arg[loop] + " not understood.");
					}
				}
				catch(ArrayIndexOutOfBoundsException aioobe)
				{
					errorExit(currentSwitch + ": expects more parameters than currently given");
				}
				catch(NumberFormatException nfeGlobal)
				{
					errorExit(currentSwitch + ": one of the parameters given should've been a number");
				}
			}

			CoffeeSaint coffeeSaint = new CoffeeSaint();
			Gui gui = null;
			if (config.getRunGui())
			{
				System.out.println("Start gui");

				Monitor monitor = null;
				gui = new Gui(config, coffeeSaint, statistics);

				if (config.getUseScreen() != null)
				{
					monitor = coffeeSaint.selectScreen(config.getUseScreen());
					if (monitor == null)
					{
						System.err.println("Screen '" + config.getUseScreen() + "' is not known.");
						System.err.println("Please use '--list-screens' to see a list of known displays.");
					}
				}
				else
				{
					GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
					GraphicsDevice gd = ge.getDefaultScreenDevice();
					JFrame f = new JFrame();
					/* create frame to draw in */
					Rectangle useable = ge.getMaximumWindowBounds();
					//f.setMaximizedBounds(useable);
					f.setLocation(useable.x, useable.y);
					f.setSize(useable.width, useable.height);
					GraphicsConfiguration gc = gd.getDefaultConfiguration();
					monitor = new Monitor(gd, gc, gc.getDevice().getIDstring(), useable);
					monitor.setJFrame(f);
				}

				JFrame f = monitor.getJFrame();

				if (config.getFullscreen() == FullScreenMode.FULLSCREEN)
				{
					System.out.println("FULLSCREEN");
					f.setUndecorated(true);
					f.setResizable(false);
					monitor.getGraphicsDevice().setFullScreenWindow(f);
				}
				else if (config.getFullscreen() == FullScreenMode.ALLMONITORS)
				{
					System.out.println("ALLMONITORS");
					f.setUndecorated(true);
					f.setResizable(false);
					Rectangle allMonitors = coffeeSaint.getDimensionsOverAllMonitors();
					f.setLocation(allMonitors.x, allMonitors.y);
					f.setSize(allMonitors.width, allMonitors.height);
				}
				else
				{
					//					f.setExtendedState(f.getExtendedState() | JFrame.MAXIMIZED_BOTH);

					if (config.getFullscreen() == FullScreenMode.UNDECORATED)
					{
						System.out.println("UNDECORATED");
						f.setUndecorated(true);
						f.setResizable(false);
					}
					else
					{
						System.out.println("WINDOWED");
					}
				}

				f.setContentPane(gui);

				RepaintManager.currentManager(gui).setDoubleBufferingEnabled(config.getDoubleBuffering());

				System.out.println("Initial paint");

				f.setTitle(getVersion());
				setIcon(coffeeSaint, f);

				f.setVisible(true);

				f.addWindowListener(new FrameListener(config, coffeeSaint));
			}

			if (config.getHTTPServerListenPort() != -1)
			{
				System.out.println("Start HTTP server");
				Thread httpServer = new Thread(new HTTPServer(config, coffeeSaint, statistics, gui));
				httpServer.setPriority(Thread.MAX_PRIORITY);
				httpServer.start();
			}

			if (config.getRunGui())
			{
				System.out.println("Start gui loop");
				gui.guiLoop();
			}
			else
			{
				System.out.println("Start daemon loop");
				daemonLoop(coffeeSaint, config);
			}
		}
		catch(Exception e)
		{
			showException(e);
		}
	}
}
