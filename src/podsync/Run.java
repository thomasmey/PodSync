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

		// process commands, if any
		if(args.length > 1) {
			if(args[1].equals("clear")) {
				ps.clear();
				return;
			}
			if(args[1].equals("list")) {
				ps.list();
				return;
			}
			if(args.length == 4 && args[1].equals("add")) {
				ps.add(args[2], args[3]);
				return;
			}
			System.err.println("Unknown command!");
		} else
			ps.run();
	}
}
