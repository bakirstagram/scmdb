package com.onevizion.scmdb.dao;

import com.onevizion.scmdb.vo.DbObject;
import com.onevizion.scmdb.vo.DbObjectType;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.onevizion.scmdb.vo.DbObjectType.*;

@Component
public class DdlDao extends AbstractDaoOra {
    private final static String FIND_ALL_DB_OBJECTS = "select object_name,\n" +
            "       object_type\n" +
            "from user_objects\n" +
            "where (object_type = 'TABLE'\n" +
            "    and generated = 'N'\n" +
            "    and object_name not like 'Z_%'" +
            "    and object_name not like '%_OLD')\n" +
            "   or object_type = 'VIEW'\n" +
            "   or object_type = 'PACKAGE'\n" +
            "   or object_type = 'PACKAGE BODY'\n" +
            "   or object_type = 'TRIGGER'\n" +
            "   or ((object_type = 'TYPE' or object_type = 'TYPE BODY')\n" +
            "     and generated = 'N'\n" +
            "     and object_name not like 'T$%')\n";

    private final static String SELECT_DDL_COMMENTS_BY_TABLE_NAME = "select table_name, dbms_metadata.get_dependent_ddl('COMMENT', table_name) from" +
            " ((select table_name from user_tab_comments" +
            "     where comments is not null)" +
            " union" +
            "  (select table_name from user_col_comments" +
            "     where comments is not null" +
            "     group by table_name)) where table_name = upper(:tableName)";

    private final static String SELECT_DDL_SEQUENCE_BY_TABLE_NAME = "select trgrs.table_name, dbms_metadata.get_ddl('SEQUENCE', depends.referenced_name)" +
            " from user_dependencies depends, user_triggers trgrs" +
            " where trgrs.trigger_name = depends.name and depends.type = 'TRIGGER'" +
            " and depends.referenced_type = 'SEQUENCE' and trgrs.table_name = upper(:tableName)" +
            " order by depends.referenced_name";

    private final static String SELECT_DDL_INDEX_BY_TABLE_NAME = "select table_name, dbms_metadata.get_ddl('INDEX', index_name)" +
            " from user_indexes where generated = 'N' and table_name=upper(:tableName) and index_name not like 'PK_%'" +
            " order by table_name asc, uniqueness desc, regexp_substr(index_name, '^\\D*') nulls first, " +
            "  to_number(regexp_substr(index_name, '\\d+'))";

    private final static String SELECT_DDL_TRIGGER_BY_TABLE_NAME = "select table_name, dbms_metadata.get_ddl('TRIGGER', trigger_name)" +
            " from user_triggers where table_name=upper(:tableName)" +
            " and trigger_name not like 'Z_%' order by nlssort(trigger_name, 'NLS_SORT = BINARY_CI')";

    private final static RowMapper<DbObject> rowMapper = (rs, rowNum) -> {
        DbObject dbObject = new DbObject();
        dbObject.setName(rs.getString(1));
        dbObject.setDdl(rs.getString(2));
        return dbObject;
    };

    private final static RowMapper<DbObject> rowMapperWithObjectType = (rs, rowNum) -> {
        DbObject dbObject = new DbObject();
        dbObject.setName(rs.getString("object_name"));
        dbObject.setType(DbObjectType.getByName(rs.getString("object_type")));
        return dbObject;
    };
    
    public void executeTransformParamStatements() {
        String plsqlBlock = "begin" +
                "\n dbms_metadata.set_transform_param(dbms_metadata.session_transform,'PRETTY',true);" +
                "\n dbms_metadata.set_transform_param(dbms_metadata.session_transform,'SQLTERMINATOR',true);" +
                "\n dbms_metadata.set_transform_param(dbms_metadata.session_transform,'SEGMENT_ATTRIBUTES',false);" +
                "\n end;";
        jdbcTemplate.execute(plsqlBlock);
    }

    public List<DbObject> extractAllDbObjectsWithoutDdl() {
        return jdbcTemplate.query(FIND_ALL_DB_OBJECTS, rowMapperWithObjectType);
    }

    public String extractDdl(DbObject dbObject) {
        String sql = "select dbms_metadata.get_ddl(upper(:dbObjType), upper(:dbObjName)) from dual";
        MapSqlParameterSource namedParams = new MapSqlParameterSource();
        namedParams.addValue("dbObjName", dbObject.getName());
        namedParams.addValue("dbObjType", dbObject.getType().toString());
        return namedParameterJdbcTemplate.queryForObject(sql, namedParams, String.class);
    }

    public List<DbObject> extractTableDependentObjectsDdl(String tableName, DbObjectType depObjType) {
        MapSqlParameterSource namedParams = new MapSqlParameterSource("tableName", tableName);

        List<DbObject> dbObjects = null;
        if (depObjType == COMMENT) {
            dbObjects = namedParameterJdbcTemplate.query(SELECT_DDL_COMMENTS_BY_TABLE_NAME, namedParams, rowMapper);
        } else if (depObjType == SEQUENCE) {
            dbObjects = namedParameterJdbcTemplate.query(SELECT_DDL_SEQUENCE_BY_TABLE_NAME, namedParams, rowMapper);
        } else if (depObjType == INDEX) {
            dbObjects = namedParameterJdbcTemplate.query(SELECT_DDL_INDEX_BY_TABLE_NAME, namedParams, rowMapper);
        } else if (depObjType == TRIGGER) {
            dbObjects = namedParameterJdbcTemplate.query(SELECT_DDL_TRIGGER_BY_TABLE_NAME, namedParams, rowMapper);
        }

        return dbObjects;
    }

    public String getTableNameByDepObject(DbObject dbObject) {
        MapSqlParameterSource namedParams = new MapSqlParameterSource();
        namedParams.addValue("objName", dbObject.getName());
        String sql;
        if (dbObject.getType() == INDEX) {
            sql = "select table_name from user_indexes where index_name = upper(:objName)";
        } else if (dbObject.getType() == TRIGGER) {
            sql = "select table_name from user_triggers where trigger_name = upper(:objName)";
        } else if (dbObject.getType() == SEQUENCE) {
            sql = "select trgrs.table_name from user_dependencies depends, user_triggers trgrs" +
                    " where trgrs.trigger_name = depends.name and depends.type = 'TRIGGER'" +
                    " and depends.referenced_type = 'SEQUENCE' and depends.referenced_name = upper(:objName)";
        } else {
            return null;
        }
        try {
            return namedParameterJdbcTemplate.queryForObject(sql, namedParams, String.class);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public DbObjectType getObjectTypeByName(String dbObjName) {
        String sql = "select 'TABLE' object_type from user_tables where table_name = upper(:dbObjName)" +
                " union all select 'VIEW' object_type from user_views where view_name = upper(:dbObjName)";
        MapSqlParameterSource namedParams = new MapSqlParameterSource();
        namedParams.addValue("dbObjName", dbObjName);
        String dbObjTypeString = namedParameterJdbcTemplate.queryForObject(sql, namedParams, String.class);
        return DbObjectType.valueOf(dbObjTypeString);
    }

    public boolean isExist(String objectName, DbObjectType objectType) {
        MapSqlParameterSource namedParams = new MapSqlParameterSource();
        namedParams.addValue("objName", objectName);
        namedParams.addValue("objType", objectType.getName().toUpperCase());
        String sql = "select case when" +
                "                count(object_name) > 0 then 'true'" +
                "            else 'false'" +
                "        end" +
                "    from user_objects where object_name = upper(:objName) and object_type = upper(:objType)";
        String boolStr = namedParameterJdbcTemplate.queryForObject(sql, namedParams, String.class);
        return Boolean.valueOf(boolStr);
    }

}