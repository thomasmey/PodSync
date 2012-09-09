package podsync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class PodSync {

	private final Map<String,String> fromToMap = new HashMap<String,String>();

	PodSync(File propsFile) throws IOException {
		
		// use on property file format as the standard java class sucks
		InputStream is = new FileInputStream(propsFile);
		BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
		String key = reader.readLine();
		String value;
		while(key != null) {
			value = reader.readLine();
			fromToMap.put(key, value);

			key = reader.readLine();
		}
		reader.close();
	}

	public void run() {

		// run
		File fromDir;
		File toDir;
		for(String key: fromToMap.keySet()) {

			fromDir = new File(key);
			toDir   = new File(fromToMap.get(key));
			
			if(toDir.isDirectory() != true) {
				System.out.println("toDir is not a directory: " + toDir);
				return;
			}
	
			if(fromDir.isDirectory() != true) {
				System.out.println("fromDir is not a directory: "+ fromDir);
				return;
			}
			
			// get youngest file from toDir
			File[] toDirFiles = toDir.listFiles();
			File[] fromDirFiles = fromDir.listFiles();
			Comparator<File> fc = new Comparator<File>() {
				    public int compare(File f2, File f1)
				    {
					return Long.valueOf(f1.lastModified()).compareTo(f2.lastModified());
				    }
			    };
			    
			// sort files by time descending
			Arrays.sort(toDirFiles, fc);
			
			// sort files by time
			Arrays.sort(fromDirFiles, fc);
			
			//toDir empty?
			if(toDirFiles.length==0) {
				// copy newest fromDirFile to toDir
				if(fromDirFiles.length > 0) {
					File cf = fromDirFiles[0];
					try {
						if(!cf.isHidden())
							copyFile(cf,toDir);
					} catch (Exception e) {
						// TODO Auto-generated catch block
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
					//System.out.println("fromDir time " + String.valueOf(cf.lastModified()));
					//System.out.println("toDir time " + String.valueOf(newestToDir));
					Long currentFileMod = Long.valueOf(cf.lastModified());
					
					// FAT fs seems to be not exact
					if (newestFileMod + 1000 == currentFileMod)
						newestFileMod = newestFileMod + 1000;
	
					if(currentFileMod.compareTo(newestFileMod) > 0) {
						try {
							System.out.println("newestFileMod= " + newestFileMod + " currentFileMod= " + currentFileMod);
							copyFile(cf,toDir);
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
							return;
						}
					}
	
				}
			}
		}
	
	}

	private void copyFile(File fromFile, File toDir) throws Exception {
		File toFile = new File(toDir.getAbsolutePath() + File.separator + fromFile.getName());
		
		BufferedInputStream bis = new BufferedInputStream(new FileInputStream(fromFile));
		BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(toFile));
		
		System.out.print("copy \"" + fromFile.getAbsolutePath() + "\"");
		System.out.println(" to \"" + toFile.getAbsolutePath() + "\"");
		
		int b = bis.read();
		
		while(b != -1) {
			bos.write(b);
			b = bis.read();
		}
		
		bis.close();
		bos.close();
		toFile.setLastModified(fromFile.lastModified());
		
	}
}

