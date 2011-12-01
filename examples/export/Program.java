/*
 * Copyright 2011 Splunk, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"): you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import com.splunk.*;
import com.splunk.sdk.Command;

import java.nio.channels.FileChannel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;

/**
 * Export.java: export an splunk entire index in XML, CSV or JSON (4.3+). The
 * return data is in strict descending time order.
 */

// in recover mode, we will duplicate messages and meta data; however,
// this is not necessarily incorrect, just redundant information.

public class Program {

    static String lastTime;
    static int nextEventOffset;

    static public void main(String[] args) {
        try {
            run(args);
        }
        catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    static int getStartNextEvent(
            int indexTimeOffset, String str, String pattern) {
        int curr = str.indexOf(pattern);
        int last = 0;
        while ((curr < indexTimeOffset) && curr > 0)  {
            last = curr;
            curr = curr + pattern.length();
            curr = str.indexOf(pattern, curr);
        }
        return last;
    }

    static int getCsvEventTimeOffset(String str) {

        // UNDONE: does this work with line-break events?

        // get first event in this buffer
        int eventStart = str.indexOf("\n");
        int eventEnd = str.indexOf("\n", eventStart + 1);
        if (eventEnd < 0)
            return -1;

        lastTime = str.substring(eventStart).split(",")[1].replace("\"","");
        nextEventOffset = eventEnd;

        // walk through events until time changes
        eventStart = eventEnd;
        while (eventEnd > 0) {
            eventEnd = str.indexOf("\n", eventStart + 1);
            if (eventEnd < 0)
                return -1;
            String time = str.substring(eventStart, eventEnd)
                    .split(",")[1]
                    .replace("\"", "");
            if (!time.equals(lastTime)) {
                return eventStart;
            }
            nextEventOffset = eventEnd;
            eventStart = eventEnd;
        }

        return -1;
    }

    static int getXmlEventTimeOffset(String str) {
        String timeKeyPattern = "<field k='_time'>";
        String timeStartPattern = "<value><text>";
        String timeEndPattern = "<";
        String eventEndPattern = "</result>";

        // get first event in this buffer. If no event end kick back
        int eventStart = str.indexOf("<result offset='");
        int eventEnd = str.indexOf("eventEndPattern", eventStart)
                + eventEndPattern.length();
        if (eventEnd < 0)
            return -1;
        int timeKeyStart = str.indexOf(timeKeyPattern, eventStart);
        int timeStart = str.indexOf(timeStartPattern, timeKeyStart)
                + timeStartPattern.length();
        int timeEnd = str.indexOf(timeEndPattern, timeStart+1);

        lastTime = str.substring(timeStart, timeEnd);
        nextEventOffset = eventEnd;

        // walk through events until time changes
        eventStart = eventEnd;
        while (eventEnd > 0) {
            eventStart = str.indexOf("<result offset='", eventStart+1);
            eventEnd = str.indexOf("</result>", eventStart)
                    + eventEndPattern.length();
            if (eventEnd < 0)
                return -1;
            timeKeyStart = str.indexOf(timeKeyPattern, eventStart);
            timeStart = str.indexOf(timeStartPattern, timeKeyStart);
            timeEnd = str.indexOf(timeEndPattern, timeStart);
            String time = str.substring(timeStart, timeEnd);
            if (!time.equals(lastTime)) {
                return eventStart;
            }
            nextEventOffset = eventEnd;
            eventStart = eventEnd;
        }

        return -1;
    }

    static int getJsonEventTimeOffset(String str) {

        String timeKeyPattern = "\"_time\":\"";
        String timeEndPattern = "\"";
        String eventEndPattern = "\"},\n";
        String eventEndPattern2 = "\"}[]";

        // get first event in this buffer. If no event end kick back
        int eventStart = str.indexOf("{\"_cd\":\"");
        int eventEnd = str.indexOf(eventEndPattern, eventStart)
                + eventEndPattern.length();
        if (eventEnd < 0)
            eventEnd = str.indexOf(eventEndPattern2, eventStart)
                    + eventEndPattern2.length();
        if (eventEnd < 0)
            return -1;

        int timeStart = str.indexOf(timeKeyPattern, eventStart)
                + timeKeyPattern.length();
        int timeEnd = str.indexOf(timeEndPattern, timeStart+1);
        lastTime = str.substring(timeStart, timeEnd);
        nextEventOffset = eventEnd;

        // walk through events until time changes
        eventStart = eventEnd;
        while (eventEnd > 0) {
            eventStart = str.indexOf("{\"_cd\":\"", eventStart+1);
            eventEnd = str.indexOf(eventEndPattern, eventStart)
                    + eventEndPattern.length();
            if (eventEnd < 0)
                eventEnd = str.indexOf(eventEndPattern2, eventStart)
                        + eventEndPattern2.length();
            if (eventEnd < 0)
                return -1;

            timeStart = str.indexOf(timeKeyPattern, eventStart)
                    + timeKeyPattern.length();
            timeEnd = str.indexOf(timeEndPattern, timeStart+1);
            String time = str.substring(timeStart, timeEnd);
            if (!time.equals(lastTime)) {
                return eventStart;
            }
            nextEventOffset = eventEnd-2;
            eventStart = eventEnd;
        }

        return -1;
    }

    static int getLastGoodEventOffset(byte[] buffer, String format)
            throws Exception {

        String str = new String(buffer);
        if (format.equals("csv"))
            return getCsvEventTimeOffset(str);
        else if (format.equals("xml"))
            return getXmlEventTimeOffset(str);
        else
            return getJsonEventTimeOffset(str);
    }

    static void cleanupTail(Writer out, String format) throws Exception {
        if (format.equals("csv"))
            out.write("\n");
        else if (format.equals("xml"))
            out.write("\n</results>\n");
        else
            out.write("[]\n");
    }

    static void run(String[] argv) throws Exception {
        Command command = Command.splunk("export").parse(argv);
        Service service = Service.connect(command.opts);

        Args args = new Args();
        final String outFilename = "export.out";
        boolean recover = false;
        boolean addEndOfLine = false;
        String format = "csv"; // default to csv

        // This example takes optional arguments:
        //
        // index-name [recover] [csv|xml|json]
        //
        // N.B. json output only valid with 4.3+

        if (command.args.length == 0)
            throw new Error("Index-name required");

        if (command.args.length > 1) {
            for (int index=1; index < command.args.length; index++) {
                if (command.args[index].equals("recover"))
                    recover = true;
                else if (command.args[index].equals("csv"))
                    format = "csv";
                else if (command.args[index].equals("xml"))
                    format = "xml";
                else if (command.args[index].equals("json"))
                    format = "json";
                else
                    throw new Error("Unknown option: " + command.args[index]);
            }
        }

        File file = new File(outFilename);
        if (file.exists() && file.isFile() && !recover)
            throw new Error("Export file exists, and no recover option");

        if (recover && file.exists() && file.isFile()) {
            // chunk backwards through the file until we find valid
            // start time. If we can't find one just start over.
            final int bufferSize = (64*1024);
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            long fptr = Math.max(file.length() - bufferSize, 0);
            long fptrEof = 0;

            while (fptr > 0) {
                byte [] buffer = new byte[bufferSize];
                raf.seek(fptr);
                raf.read(buffer, 0, bufferSize);
                int eventTimeOffset = getLastGoodEventOffset(buffer, format);
                if (eventTimeOffset != -1) {
                    fptrEof = nextEventOffset + fptr;
                    break;
                }
                fptr = fptr - bufferSize;
            }

            if (fptr < 0)
                fptrEof = 0; // didn't find a valid event, so start over.
            else
                args.put("latest_time", lastTime);
                addEndOfLine = true;

            FileChannel fc = raf.getChannel();
            fc.truncate(fptrEof);
        } else
        if (!file.createNewFile())
            throw new Error("Failed to create output file");

        // search args
        args.put("timeout", "60");          // don't keep search around
        args.put("output_mode", format);    // output in specific format
        args.put("ealiest_time", "0.000");  // always to beginning of index
        args.put("time_format", "%s.%Q");   // epoch time plus fraction
        String search = String.format("search index=%s *", command.args[0]);

        //System.out.println("search: " + search + ", args: " + args);
        InputStream is = service.export(search, args);

        // use UTF8 sensitive reader/writers
        InputStreamReader isr = new InputStreamReader(is, "UTF8");
        FileOutputStream os = new FileOutputStream(file, true);
        Writer out = new OutputStreamWriter(os, "UTF8");

        // read/write 8k at a time if possible
        char [] xferBuffer = new char[8192];
        boolean once = true;

        // if superfluous meta-data is not needed, or specifically
        // wants to be removed, one would clean up the first read
        // buffer on a format by format basis,
        while (true) {
            if (addEndOfLine && once) {
                cleanupTail(out, format);
                once = false;
            }
            int bytesRead = isr.read(xferBuffer);
            if (bytesRead == -1) break;
            out.write(xferBuffer, 0, bytesRead);
        }

        isr.close();
        out.close();
    }
}