#!/bin/sh
# 数据库操作 - H2升级和恢复

# 升级 H2 数据库
upgrade_h2() {
  if [ -f /data/h2.version.txt ]; then
    log_info "H2 database already upgraded"
    return 0
  fi

  log_info "Attempting to upgrade H2 database"

  # 确定数据库文件位置
  file=/opt/atv/data/data
  [ -f /data/atv.mv.db ] && file=/data/atv

  log_info "Exporting database from: $file"

  # 导出数据库
  if /jre/bin/java -cp /h2-2.1.214.jar org.h2.tools.Script \
    -url jdbc:h2:file:$file \
    -user sa -password password \
    -script backup.sql; then

    log_info "Importing database with new H2 version"

    # 删除旧数据库文件
    rm -f ${file}.mv.db ${file}.trace.db

    # 导入到新版本
    if /jre/bin/java -cp /opt/atv/BOOT-INF/lib/h2-*.jar org.h2.tools.RunScript \
      -url jdbc:h2:file:$file \
      -user sa -password password \
      -script backup.sql; then

      echo "2.3.232" > /data/h2.version.txt
      log_info "H2 database upgraded to 2.3.232"
      return 0
    else
      log_error "Failed to import database"
      return 1
    fi
  else
    log_error "Failed to export database"
    return 1
  fi
}

# 从备份恢复数据库
restore_database() {
  if [ -f "/data/database.zip" ]; then
    log_info "Restoring database from backup"

    rm -f /data/atv.mv.db /data/atv.trace.db

    /jre/bin/java -cp /opt/atv/BOOT-INF/lib/h2-*.jar org.h2.tools.RunScript \
      -url jdbc:h2:/data/atv \
      -user sa -password password \
      -script /data/database.zip \
      -options compression zip

    if check_success "Failed to restore database"; then
      rm -f /data/database.zip
      log_info "Database restored successfully"
      return 0
    fi
    return 1
  fi
  return 0
}
