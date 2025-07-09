package podsync;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

public class PodSync {

	private static Logger log = Logger.getLogger("PodSync");

	private final JsonObject config;

	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {

		JsonObject config = null;
		try(InputStream is = PodSync.class.getResourceAsStream("/config.json");
				JsonReader jr = Json.createReader(is)) {
			config = jr.readObject();
		}

//		Map rssState = new HashMap<>();
//		File rsf = new File((String) config.get(Keyword.newKeyword("rssState")));
//		if(rsf.exists() && rsf.isFile()) {
//			Parseable rsp = Parsers.newParseable(new FileReader(rsf));
//			rssState = (Map) parser.nextValue(rsp);
//		}

		PodSync ps = new PodSync(config);
		ps.run();
	}

	PodSync(JsonObject config) throws IOException {
		this.config = config;
	}

	public void run() {
		config.getJsonArray("podcasts").parallelStream().forEach(p -> processPodcast((JsonObject) p));
	}

	private void processPodcast(JsonObject podcast) {

		List<Map> items = new ArrayList<>();

		DefaultHandler handler = new DefaultHandler() {
			DateFormat rfc822 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
			Map currentItem;
			String currentValue;
			Attributes currentAttributes;
			int currentLevel;

			@Override
			public void startElement(String arg0, String arg1, String qName, Attributes attributes) throws SAXException {
				if("item".equals(qName)) {
					currentItem = new HashMap<>();
				}
				currentAttributes = attributes;
				currentLevel++;
				currentValue = "";
			}
			@Override
			public void characters(char[] ch, int start, int length) throws SAXException {
				currentValue += new String(ch, start, length);
			}
			@Override
			public void endElement(String arg0, String arg1, String qName) throws SAXException {
				currentLevel--;
				switch(qName) {
				case "item":
					items.add(currentItem);
					currentItem = null;
					break;
				case "title":
					if(currentItem != null) currentItem.put(qName, currentValue);
					break;
				case "enclosure":
					if(currentItem != null) {
						try {
							currentItem.put("url", new URL(currentAttributes.getValue("url")));
							currentItem.put("length", Long.valueOf(currentAttributes.getValue("length").trim()));
							currentItem.put("type", currentAttributes.getValue("type"));
						} catch (MalformedURLException e) {
						}
					}
					break;
				case "pubDate":
					if(currentItem != null) {
						try {
							Date d = rfc822.parse(currentValue);
							currentItem.put(qName, d);
						} catch (ParseException e) {
							log.log(Level.SEVERE, "failed!", e);
						}
					}
					break;
				}
			}
		};

		URI uri = null;
		try {
			Client client = ClientBuilder.newClient();

			URI rss = URI.create(podcast.getString("url"));
			String username = podcast.getString("username", null);
			String password = podcast.getString("password", null);
			String etag = null;
			if(username != null && password != null) {
				client.register(HttpAuthenticationFeature.basic(username, password));
			}

			uri = rss;
			loop:
				for(;;) {
					log.log(Level.INFO, "Fetching RSS feed from {0}", uri);
					try(Response r = client.target(uri).request().header(HttpHeaders.IF_NONE_MATCH, etag).head()) {
						log.log(Level.INFO, "result {0}", r.getStatus());
						switch(r.getStatusInfo().getFamily()) {
						case SUCCESSFUL:
							try(Response rd = client.target(uri).request().header(HttpHeaders.IF_NONE_MATCH, etag).get()) {
								if(rd.getStatus() == Response.Status.OK.getStatusCode()) {
									EntityTag entityTag = rd.getEntityTag();
									InputStream in = (InputStream) rd.getEntity();
									SAXParserFactory.newInstance().newSAXParser().parse(in, handler);
									break loop;
								}
							}
							break;
						case REDIRECTION:
							uri = r.getLocation();
							if(uri != null) {
								log.log(Level.INFO, "redirect to {0}", uri);
								continue loop;
							}
							log.log(Level.WARNING, "Redirection without location for {0}", uri);
							return;
						case CLIENT_ERROR:
							log.log(Level.SEVERE, "Client error for {0} - Check your credentials! Ignoring for now", uri);
							return;
						case SERVER_ERROR:
							log.log(Level.SEVERE, "Server error for {0} - ignoring for now", uri);
							return;
						}
					}
				}
		} catch (jakarta.ws.rs.ProcessingException | IOException | SAXException | ParserConfigurationException e) {
			log.log(Level.SEVERE, "Failed to fetch RSS " + uri, e);
		}

		Collections.sort(items, (i1, i2) -> ((Date)i2.get("pubDate")).compareTo((Date) i1.get("pubDate")));

		File fromDir = new File(podcast.getString("fromDir"));

		// check input
		if(fromDir.isDirectory() != true) {
			boolean r = fromDir.mkdir();
			if(r) log.log(Level.SEVERE, "fromDir: {0} created", fromDir);
			else return;
		}

		File[] fromDirFiles = fromDir.listFiles();

		// check if we need to download the latest item
		if(items.size() > 0) {
			Map latestItem = items.get(0);
			URL url = (URL) latestItem.get("url");
			String[] segments = url.getPath().split("/");
			String targetName = segments[segments.length - 1];
//			if(!Arrays.stream(fromDirFiles).anyMatch( f -> f.getName().equals(targetName))) {
				try(Response r = ClientBuilder.newClient().target(url.toURI()).request().head()) {
					MultivaluedMap<String, Object> headers = r.getHeaders();
					File fromFile = new File(fromDir, targetName);
					long remoteLength = Long.parseLong((String) headers.getFirst("content-length"));
					long localLength = fromFile.isFile() ? fromFile.length() : 0;
					if(localLength < remoteLength) {
						log.log(Level.INFO, "Downloading {0} from {1} - local {2} remote {3}", new Object[] {targetName, url, localLength, remoteLength});
						HttpURLConnection uc = followRedirects(url, localLength);
						try(InputStream in = uc.getInputStream();
							OutputStream out = new FileOutputStream(fromFile, true)) {
							in.transferTo(out);
						}
					}
				} catch (IOException | IllegalStateException | URISyntaxException e) {
					log.log(Level.SEVERE, "Failed to downloading " + url, e);
					return;
				}
//			}
		}

		// when toDir doesn't exist, only download
		String td = (String) podcast.getString("toDir");
		if(td == null)
			return;

		File toDir = new File(td);
		File[] roots = File.listRoots();
		boolean isMounted = Arrays.stream(roots).anyMatch(r -> r.toPath().getRoot().equals(toDir.toPath().getRoot()));
		if(!isMounted) {
			log.log(Level.SEVERE, "toDir is not mounted: {0}", toDir);
			return;
		}

		if(!toDir.exists()) {
			toDir.mkdir();
		} else {
			if(toDir.isDirectory() != true) {
				log.log(Level.SEVERE, "toDir is not a directory: {0}", toDir);
				return;
			}
		}

		fromDirFiles = fromDir.listFiles();
		File[] toDirFiles = toDir.listFiles();
		// get youngest file from toDir
		Comparator<File> fc = Comparator.comparing(File::lastModified);

		// sort files by time descending
		Arrays.sort(toDirFiles, fc);
		Arrays.sort(fromDirFiles, fc);

		//toDir empty?
		if(toDirFiles.length == 0) {
			// copy newest fromDirFile to toDir
			if(fromDirFiles.length > 0) {
				File cf = fromDirFiles[0];
				try {
					if(!cf.isHidden())
						copyFile(cf,toDir);
				} catch (Exception e) {
					e.printStackTrace();
					return;
				}
			}
		} else {
			// copy all files from fromDir newer than newest file in toDir
			long newestFileMod = toDirFiles[0].lastModified();

			// FIXME: reverse loop!
			for(int i=0; i<fromDirFiles.length;i++) {
				File cf=fromDirFiles[i];
				if(cf.isHidden())
					continue;
				log.log(Level.FINE, "fromDir time {0}", String.valueOf(cf.lastModified()));
//				log.log(Level.FINE, "toDir time {0}", String.valueOf(newestToDir));
				Long currentFileMod = Long.valueOf(cf.lastModified());

				// FAT fs seems to be not exact
				if (newestFileMod + 1000 == currentFileMod)
					newestFileMod = newestFileMod + 1000;

				if(currentFileMod.compareTo(newestFileMod) > 0) {
					try {
						log.log(Level.INFO, "newestFileMod= {0} currentFileMod= {1}", new Object[] {newestFileMod, currentFileMod});
						copyFile(cf,toDir);
					} catch (Exception e) {
						e.printStackTrace();
						return;
					}
				}
			}
		}
	}

	private static HttpURLConnection followRedirects(URL url, long resumeLength) {
		try {
			HttpURLConnection.setFollowRedirects(true);
			HttpURLConnection uc;
			while(true) {
				uc = (HttpURLConnection) url.openConnection();
				uc.setRequestProperty("range", "bytes="+ resumeLength + "-");
				int rc = uc.getResponseCode();
				switch(rc) {
				case HttpURLConnection.HTTP_MOVED_PERM:
				case HttpURLConnection.HTTP_MOVED_TEMP:
					String location = uc.getHeaderField("Location");
					URL next = new URL(url, location);
					url = next;
					break;
				case HttpURLConnection.HTTP_OK:
				case HttpURLConnection.HTTP_PARTIAL:
					return uc;
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		throw new IllegalStateException("couldn't retrieve URL " + url);
	}

	private static void copyFile(File fromFile, File toDir) throws IOException {
		File toFile = new File(toDir.getAbsolutePath() + File.separator + fromFile.getName());

		log.log(Level.INFO, "copy from \"{0}\" to \"{1}\"", new Object[] {fromFile.getAbsolutePath(), toFile.getAbsolutePath()});
		try {
			Files.copy(fromFile.toPath(), toFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} finally {
			toFile.setLastModified(fromFile.lastModified());
		}
	}
}

