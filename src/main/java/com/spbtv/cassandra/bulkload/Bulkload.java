package com.spbtv.cassandra.bulkload;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.supercsv.io.CsvListReader;
import org.supercsv.prefs.CsvPreference;
import org.apache.cassandra.config.Config;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.io.sstable.CQLSSTableWriter;

import com.google.common.base.Joiner;

/**
 * Usage: java bulkload.BulkLoad <keyspace> <absolute/path/to/schema.cql> <absolute/path/to/input.csv> <absolute/path/to/output/dir> [optional csv prefs in JSON - default is "{\"col_sep\":\",\", \"quote_char\":\"'\"}" ]
 */
public class Bulkload {

    private static final CsvPreference SINGLE_QUOTED_COMMA_DELIMITED = new CsvPreference.Builder(
            '\'', ',', "\n").build();

    private static String readFile(String path, Charset encoding)
            throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded, encoding);
    }

    private static Map<String, String> extractColumns(String schema) {
        Map<String, String> cols = new HashMap<>();
        Pattern columnsPattern = Pattern.compile(".*?\\((.*?)(?:,\\s*PRIMARY KEY.*)?\\).*");
        Matcher m = columnsPattern.matcher(schema);
        if (m.matches()) {
            for (String col : m.group(1).split(",")) {
                String[] name_type_prim = col.trim().split("\\s+");
                if (name_type_prim.length <= 4 && !name_type_prim[0].toUpperCase().equals("PRIMARY"))
                    cols.put(name_type_prim[0], name_type_prim[1]);
            }

        } else throw new RuntimeException("Could not extract columns from provided schema.");
        return cols;
    }

    private static Set<String> extractPrimaryColumns(String schema) {
        Set<String> primary = new HashSet<>();

        // Primary key defined on the same line as the corresponding column
        Pattern pattern = Pattern.compile(".*?(\\w+)\\s+\\w+\\s+PRIMARY KEY.*");
        Matcher m = pattern.matcher(schema);
        if (m.matches()) {
            primary.add(m.group(1));
            return primary;
        }

        // Multi-columns primary key defined on a separate line
        pattern = Pattern.compile(".*PRIMARY KEY\\s*\\(\\s*\\((.*?)\\).*\\).*");
        m = pattern.matcher(schema);
        if (m.matches()) {
            for (String col : m.group(1).split(",")) {
                primary.add(col.trim());
            }
            return primary;
        }

        // Single-column primary key defined on a separate line
        pattern = Pattern.compile(".*PRIMARY KEY\\s*\\(\\s*\\(?\\s*(\\w+)\\s*\\)?,?.*\\).*");
        m = pattern.matcher(schema);
        if (m.matches()) {
            primary.add(m.group(1));
            return primary;
        }


        throw new RuntimeException("Could not extract primary columns from provided schema.");
    }

    private static String extractTable(String schema, String keyspace) {
        Pattern columnsPattern = Pattern.compile(".*\\s+" + keyspace + "\\.(\\w+)\\s*\\(.*");
        Matcher m = columnsPattern.matcher(schema);
        if (m.matches()) {
            return m.group(1).trim();
        }
        throw new RuntimeException("Could not extract table name from provided schema.");
    }

    private static Object parse(String value, String type, boolean columnIsPrimary) {
        // We use Java types here based on
        // http://www.datastax.com/drivers/java/2.0/com/datastax/driver/core/DataType.Name.html#asJavaClass%28%29

        if (value == null) {
            if (columnIsPrimary) {
                if (type.toLowerCase().equals("text")) return "";
                else throw new RuntimeException("A primary column of type " + type + " was null.");
            }
            return null;
        }
        switch (type.toLowerCase()) {
            case "text":
                return value;
            case "float":
                return Float.parseFloat(value);
            case "int":
                return Integer.parseUnsignedInt(value, 16);
            case "boolean":
                return Boolean.parseBoolean(value);
            case "set<text>":
                JSONParser parser = new JSONParser();
                try {
                    JSONArray json_list = (JSONArray) parser.parse(value);
                    Set<String> set = new HashSet<String>();
                    for (int i = 0; i < json_list.size(); i++) {
                        set.add(json_list.get(i).toString());
                    }
                    return set;
                } catch (ParseException e) {
                    throw new RuntimeException("Cannot parse provided set<text> column. Got " + value + ".");
                }
            default:
                throw new RuntimeException("Cannot parse type '" + type + "'.");
        }
    }

    private static CsvPreference parseCsvPrefs(String prefs) throws Exception {
        JSONParser parser = new JSONParser();
        JSONObject hash = (JSONObject) parser.parse(prefs);
        char col_sep = ((String) hash.get("col_sep")).charAt(0);
        char quote_char = ((String) hash.get("quote_char")).charAt(0);
        return new CsvPreference.Builder(quote_char, col_sep, "\n").build();
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("[v3@daimon]usage: java bulkload.BulkLoad <keyspace> <path/to/schema.cql> <path/to/input.csv> <path/to/output/dir> [optional csv prefs json - default is {\"col_sep\":\",\", \"quote_char\":\"'\"} ]");
            return;
        }

        String keyspace = args[0];
        String schema_path = args[1];
        String csv_path = args[2];
        String output_path = args[3];

        System.out.println(csv_path);

        CsvPreference csv_prefs = SINGLE_QUOTED_COMMA_DELIMITED;
        if (args.length >= 5) {
            try {
                csv_prefs = parseCsvPrefs(args[4]);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("Cannot parse provided csv prefs: " + args[4] + ".");
            }
        }

        String schema = null;
        try {
            schema = readFile(schema_path, StandardCharsets.UTF_8).replace("\n", " ").replace("\r", " ");
        } catch (IOException e) {
            e.printStackTrace();
        }

        Map<String, String> columns = extractColumns(schema);
        String table = extractTable(schema, keyspace);
        Set<String> primaryColumns = extractPrimaryColumns(schema);

        System.out.println(String.format("Converting CSV to SSTables for table '%s'...", table));

        // magic!
        Config.setClientMode(true);

        // Create output directory that has keyspace and table name in the path
        File outputDir = new File(output_path + File.separator + keyspace
                + File.separator + table);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new RuntimeException("Cannot create output directory: "
                    + outputDir);
        }

        try (
                BufferedReader reader = new BufferedReader(new FileReader(csv_path));
                CsvListReader csvReader = new CsvListReader(reader, csv_prefs)) {

//			String [] header = csvReader.getHeader(true);
            String[] header = {"md51", "md52", "value"};
//			System.out.println(Arrays.asList(header));

            String insert_stmt = String.format("INSERT INTO %s.%s (\"%s\", \"%s\", \"%s\") values (?, ?, ?)", keyspace, table, header[0], header[1], header[2]);
//			System.out.println(insert_stmt);
            // Prepare SSTable writer
            CQLSSTableWriter.Builder builder = CQLSSTableWriter.builder();
            // set output directory
            builder.inDirectory(outputDir)
                    // set target schema
                    .forTable(schema)
                    // set CQL statement to put data
                    .using(insert_stmt)
                    // set partitioner if needed
                    // default is Murmur3Partitioner so set if you use different
                    // one.
                    .withPartitioner(new Murmur3Partitioner());
            CQLSSTableWriter writer = builder.build();

            // Write to SSTable while reading data
            List<String> line;
            while ((line = csvReader.read()) != null) {
                Map<String, Object> row = new HashMap<>();
                String md51 = line.get(0);
                String md52 = line.get(1);
                String md51_2 = String.format("%s%s", md51, md52.substring(0, 2));
                String md52_2 = md52.substring(2);
                row.put("md51", Integer.parseUnsignedInt(md51_2, 16));
                row.put("md52", md52_2);
//				System.out.println(String.format("%s, %s", md51_2, md52_2));
                row.put("value", line.get(2));
                writer.addRow(row);
            }

            writer.close();

        } catch (InvalidRequestException | IOException e) {
            e.printStackTrace();
        }

        System.out.println(String.format("Done: %s", csv_path));
    }
}