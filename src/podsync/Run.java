package podsync;

import java.io.File;
import java.io.IOException;

public class Run {

	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {

		File propsFile = null;
		if(args.length == 0)
			propsFile = new File("sync-list.txt");
		else
			propsFile = new File(args[0]);

		PodSync ps = new PodSync(propsFile);

		ps.run();
	}
}
