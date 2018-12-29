package com.onevizion.scmdb;

import com.onevizion.scmdb.vo.DbCnnCredentials;
import com.onevizion.scmdb.vo.SchemaType;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static com.onevizion.scmdb.vo.SchemaType.*;
import static java.util.Arrays.asList;

public class AppArguments {
    private File scriptsDirectory;
    private File ddlsDirectory;
    private Map<SchemaType, DbCnnCredentials> credentials = new HashMap<>();
    private boolean genDdl;
    private boolean executeScripts;
    private boolean useColorLogging = true;
    private boolean all = false;
    private boolean omitChanged = false;

    private final static String DDL_DIRECTORY_NAME = "ddl";

    private AppArguments() {}

    public AppArguments(String[] args) {
        parse(args);
    }

    void parse(String[] args) {
        OptionParser parser = new OptionParser();
        OptionSpec<String> ownerSchemaOption = parser.accepts("owner-schema").withRequiredArg().ofType(String.class);
        OptionSpec<String> userSchemaOption = parser.accepts("user-schema").withRequiredArg().ofType(String.class);
        OptionSpec<String> rptSchemaOption = parser.accepts("rpt-schema").withRequiredArg().ofType(String.class);
        OptionSpec<String> pkgSchemaOption = parser.accepts("pkg-schema").withRequiredArg().ofType(String.class);
        OptionSpec<File> scriptsDirectoryOption = parser.accepts("scripts-dir").withRequiredArg().ofType(File.class);

        OptionSpec execOption = parser.acceptsAll(asList("e", "exec"));
        OptionSpec genDdlOption = parser.acceptsAll(asList("d", "gen-ddl"));
        OptionSpec allOption = parser.acceptsAll(asList("a", "all"));
        OptionSpec noColorOption = parser.acceptsAll(asList("n", "no-color"));
        OptionSpec omitChangedOption = parser.acceptsAll(asList("o", "omit-changed"));

        OptionSet options = parser.parse(args);

        if(!options.has(ownerSchemaOption) || !options.has(scriptsDirectoryOption)){
            throw new IllegalArgumentException("--owner-schema and --scripts-dir are required parameters.");
        }

        credentials.put(OWNER, DbCnnCredentials.create(options.valueOf(ownerSchemaOption)));
        createCredentials(USER, options, userSchemaOption);
        createCredentials(RPT, options, rptSchemaOption);
        createCredentials(PKG, options, pkgSchemaOption);

        scriptsDirectory = options.valueOf(scriptsDirectoryOption);
        if (!scriptsDirectory.exists() || !scriptsDirectory.isDirectory()) {
            throw new IllegalArgumentException("Path [" + scriptsDirectory.getAbsolutePath() + "] doesn't exists or isn't a directory." +
                    " [--scripts-dir] should contains absolute path and points to scripts directory");
        }
        if(options.has(genDdlOption)){
            ddlsDirectory = new File(scriptsDirectory.getParentFile().getAbsolutePath() + File.separator +
                    DDL_DIRECTORY_NAME);
            if (!ddlsDirectory.exists() || !ddlsDirectory.isDirectory()) {
                throw new IllegalArgumentException("Path [" + ddlsDirectory.getAbsolutePath() + "] doesn't exists or isn't a directory." +
                        " Can't find ddl directory");
            }
        }

        if (options.has(execOption) && options.has(genDdlOption)) {
            throw new IllegalArgumentException("You can't specify both --gen-ddl and --exec arguments. Choose one.");
        }
        executeScripts = options.has(execOption);
        genDdl = options.has(genDdlOption);
        all = options.has(allOption);
        useColorLogging = !options.has(noColorOption);
        omitChanged = options.has(omitChangedOption);
    }

    private void createCredentials(SchemaType schemaType, OptionSet options, OptionSpec<String> schemaOption) {
        if (options.has(schemaOption)) {
            credentials.put(schemaType, DbCnnCredentials.create(options.valueOf(schemaOption)));
        } else {
            String ownerConnectionString = credentials.get(OWNER).getConnectionString();
            credentials.put(schemaType, DbCnnCredentials.create(DbCnnCredentials.genCnnStrForSchema(ownerConnectionString,
                    schemaType)));
        }
    }

    public File getScriptsDirectory() {
        return scriptsDirectory;
    }

    public File getDdlsDirectory() {
        return ddlsDirectory;
    }

    public DbCnnCredentials getDbCredentials(SchemaType schemaType) {
        return credentials.get(schemaType);
    }

    public boolean isGenDdl() {
        return genDdl;
    }

    public boolean isExecuteScripts() {
        return executeScripts;
    }

    public boolean isUseColorLogging() {
        return useColorLogging;
    }

    public boolean isAll() {
        return all;
    }

    public boolean isOmitChanged() {
        return omitChanged;
    }

    public boolean isReadAllFilesContent() {
        return genDdl || !omitChanged;
    }
}
