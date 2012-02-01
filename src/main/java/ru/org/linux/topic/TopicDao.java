/*
 * Copyright 1998-2012 Linux.org.ru
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package ru.org.linux.topic;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.org.linux.gallery.Screenshot;
import ru.org.linux.group.BadGroupException;
import ru.org.linux.group.Group;
import ru.org.linux.group.GroupDao;
import ru.org.linux.poll.*;
import ru.org.linux.section.SectionNotFoundException;
import ru.org.linux.section.SectionScrollModeEnum;
import ru.org.linux.section.SectionService;
import ru.org.linux.site.MessageNotFoundException;
import ru.org.linux.site.ScriptErrorException;
import ru.org.linux.spring.Configuration;
import ru.org.linux.spring.dao.DeleteInfoDao;
import ru.org.linux.spring.dao.MsgbaseDao;
import ru.org.linux.user.*;
import ru.org.linux.util.LorHttpUtils;

import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

/**
 * Операции над сообщениями
 */

@Repository
public class TopicDao {
  private static final Log logger = LogFactory.getLog(TopicDao.class);

  @Autowired
  private GroupDao groupDao;

  @Autowired
  private PollDao pollDao;

  @Autowired
  private TagDao tagDao;

  @Autowired
  private UserEventsDao userEventsDao;

  @Autowired
  private SectionService sectionService;

  @Autowired
  private Configuration configuration;
  
  @Autowired
  private MsgbaseDao msgbaseDao;

  @Autowired
  private DeleteInfoDao deleteInfoDao;

  /**
   * Запрос получения полной информации о топике
   */
  private static final String queryMessage = "SELECT " +
        "postdate, topics.id as msgid, userid, topics.title, " +
        "topics.groupid as guid, topics.url, topics.linktext, ua_id, " +
        "urlname, havelink, section, topics.sticky, topics.postip, " +
        "postdate<(CURRENT_TIMESTAMP-sections.expire) as expired, deleted, lastmod, commitby, " +
        "commitdate, topics.stat1, postscore, topics.moderate, notop, " +
        "topics.resolved, restrict_comments, minor " +
        "FROM topics " +
        "INNER JOIN groups ON (groups.id=topics.groupid) " +
        "INNER JOIN sections ON (sections.id=groups.section) " +
        "WHERE topics.id=?";
  /**
   * Удаление топика
   */
  private static final String updateDeleteMessage = "UPDATE topics SET deleted='t',sticky='f' WHERE id=?";

  private static final String queryEditInfo = "SELECT * FROM edit_info WHERE msgid=? ORDER BY id DESC";

  private static final String queryTags = "SELECT tags_values.value FROM tags, tags_values WHERE tags.msgid=? AND tags_values.id=tags.tagid ORDER BY value";

  private static final String updateUndeleteMessage = "UPDATE topics SET deleted='f' WHERE id=?";
  private static final String updateUneleteInfo = "DELETE FROM del_info WHERE msgid=?";

  private static final String queryOnlyMessage = "SELECT message FROM msgbase WHERE id=?";

  private static final String queryTopicsIdByTime = "SELECT id FROM topics WHERE postdate>=? AND postdate<?";

  public static final String queryTimeFirstTopic = "SELECT min(postdate) FROM topics WHERE postdate!='epoch'::timestamp";

  private JdbcTemplate jdbcTemplate;
  private NamedParameterJdbcTemplate namedJdbcTemplate;
  private SimpleJdbcInsert editInsert;

  @Autowired
  private UserDao userDao;

  @Autowired
  public void setDataSource(DataSource dataSource) {
    jdbcTemplate = new JdbcTemplate(dataSource);
    namedJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    editInsert =
      new SimpleJdbcInsert(dataSource)
        .withTableName("edit_info")
        .usingColumns("msgid", "editor", "oldmessage", "oldtitle", "oldtags", "oldlinktext", "oldurl");
  }

  /**
   * Время создания первого топика
   * @return время
   */
  public Timestamp getTimeFirstTopic() {
    return jdbcTemplate.queryForObject(queryTimeFirstTopic, Timestamp.class);
  }

  /**
   * Получить содержимое топика
   * @param message топик
   * @return содержимое
   */
  public String getMessage(Topic message) {
    return jdbcTemplate.queryForObject(queryOnlyMessage, String.class, message.getId());
  }

  /**
   * Получить сообщение по id
   * @param id id нужного сообщения
   * @return сообщение
   * @throws MessageNotFoundException при отсутствии сообщения
   */
  public Topic getById(int id) throws MessageNotFoundException {
    Topic message;
    try {
      message = jdbcTemplate.queryForObject(queryMessage, new RowMapper<Topic>() {
        @Override
        public Topic mapRow(ResultSet resultSet, int i) throws SQLException {
          return new Topic(resultSet);
        }
      }, id);
    } catch (EmptyResultDataAccessException exception) {
      throw new MessageNotFoundException(id);
    }
    return message;
  }

  /**
   * Получить group message
   * @param message message
   * @return group
   * @throws BadGroupException если что-то неправильно
   */
  public Group getGroup(Topic message) throws BadGroupException {
    return groupDao.getGroup(message.getGroupId());
  }

  /**
   * Получить список топиков за месяц
   * @param year год
   * @param month месяц
   * @return список топиков
   */
  public List<Integer> getMessageForMonth(int year, int month){
    Calendar calendar = Calendar.getInstance();
    calendar.set(year, month, 1);
    Timestamp ts_start = new Timestamp(calendar.getTimeInMillis());
    calendar.add(Calendar.MONTH, 1);
    Timestamp ts_end = new Timestamp(calendar.getTimeInMillis());
    return jdbcTemplate.query(queryTopicsIdByTime, new RowMapper<Integer>() {
      @Override
      public Integer mapRow(ResultSet resultSet, int i) throws SQLException {
        return resultSet.getInt("id");
      }
    }, ts_start, ts_end);
  }

  /**
   * Получить информации о редактировании топика
   * @param id id топика
   * @return список изменений топика
   */
  public List<EditInfoDto> getEditInfo(int id) {
    final List<EditInfoDto> editInfoDTOs = new ArrayList<EditInfoDto>();
    jdbcTemplate.query(queryEditInfo, new RowCallbackHandler() {
      @Override
      public void processRow(ResultSet resultSet) throws SQLException {
        EditInfoDto editInfoDTO = new EditInfoDto();
        editInfoDTO.setId(resultSet.getInt("id"));
        editInfoDTO.setMsgid(resultSet.getInt("msgid"));
        editInfoDTO.setEditor(resultSet.getInt("editor"));
        editInfoDTO.setOldmessage(resultSet.getString("oldmessage"));
        editInfoDTO.setEditdate(resultSet.getTimestamp("editdate"));
        editInfoDTO.setOldtitle(resultSet.getString("oldtitle"));
        editInfoDTO.setOldtags(resultSet.getString("oldtags"));
        editInfoDTOs.add(editInfoDTO);
      }
    }, id);
    return editInfoDTOs;
  }

  /**
   * Получить тэги топика
   * TODO возможно надо сделать TagDao ?
   * @param message топик
   * @return список тэгов
   */
  public ImmutableList<String> getTags(Topic message) {
    final Builder<String> tags = ImmutableList.builder();

    jdbcTemplate.query(queryTags, new RowCallbackHandler() {
      @Override
      public void processRow(ResultSet resultSet) throws SQLException {
        tags.add(resultSet.getString("value"));
      }
    }, message.getId());

    return tags.build();
  }

  /**
   * Удаление топика и если удаляет модератор изменить автору score
   * @param message удаляемый топик
   * @param user удаляющий пользователь
   * @param reason прчина удаления
   * @param bonus дельта изменения score автора топика
   * @throws UserErrorException генерируется если некорректная делта score
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void deleteWithBonus(Topic message, User user, String reason, int bonus) throws UserErrorException {
    jdbcTemplate.update(updateDeleteMessage, message.getId());
    if (user.isModerator() && bonus!=0 && user.getId()!=message.getUid()) {
      if (bonus>20 || bonus<0) {
        throw new UserErrorException("Некорректное значение bonus");
      }
      userDao.changeScore(message.getUid(), -bonus);
    }

    deleteInfoDao.insert(message.getId(), user, reason, -bonus);
  }

  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void undelete(Topic message) {
    jdbcTemplate.update(updateUndeleteMessage, message.getId());
    jdbcTemplate.update(updateUneleteInfo, message.getId());
  }

  private int allocateMsgid() {
    return jdbcTemplate.queryForInt("select nextval('s_msgid') as msgid");
  }

  /**
   * Сохраняем новое сообщение
   *
   * @param msg
   * @param request
   * @param scrn
   * @param user
   * @return
   * @throws IOException
   * @throws ScriptErrorException
   */
// call in @Transactional environment
  private int saveNewMessage(
          final Topic msg,
          final HttpServletRequest request,
          Screenshot scrn,
          final User user,
          String text
  ) throws  IOException,  ScriptErrorException {
    final Group group = groupDao.getGroup(msg.getGroupId());

    final int msgid = allocateMsgid();

    String url = msg.getUrl();
    String linktext = msg.getLinktext();

    if (group.isImagePostAllowed()) {
      if (scrn == null) {
        throw new ScriptErrorException("scrn==null!?");
      }

      Screenshot screenShot = scrn.moveTo(configuration.getHTMLPathPrefix() + "/gallery", Integer.toString(msgid));

      url = "gallery/" + screenShot.getMainFile().getName();
      linktext = "gallery/" + screenShot.getIconFile().getName();
    }

    final String finalUrl = url;
    final String finalLinktext = linktext;
    jdbcTemplate.execute(
            "INSERT INTO topics (groupid, userid, title, url, moderate, postdate, id, linktext, deleted, ua_id, postip) VALUES (?, ?, ?, ?, 'f', CURRENT_TIMESTAMP, ?, ?, 'f', create_user_agent(?),?::inet)",
            new PreparedStatementCallback<String>() {
              @Override
              public String doInPreparedStatement(PreparedStatement pst) throws SQLException, DataAccessException {
                pst.setInt(1, group.getId());
                pst.setInt(2, user.getId());
                pst.setString(3, msg.getTitle());
                pst.setString(4, finalUrl);
                pst.setInt(5, msgid);
                pst.setString(6, finalLinktext);
                pst.setString(7, request.getHeader("User-Agent"));
                pst.setString(8, msg.getPostIP());
                pst.executeUpdate();

                return null;
              }
            }
    );

    // insert message text
    jdbcTemplate.update(
            "INSERT INTO msgbase (id, message, bbcode) values (?,?, ?)",
            msgid, text, true
    );

    String logmessage = "Написана тема " + msgid + ' ' + LorHttpUtils.getRequestIP(request);
    logger.info(logmessage);

    return msgid;
  }

  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public int addMessage(
          HttpServletRequest request,
          AddTopicRequest form,
          String message,
          Group group,
          User user,
          Screenshot scrn,
          Topic previewMsg,
          Set<User> userRefs
  ) throws IOException, ScriptErrorException, UserErrorException {
    final int msgid = saveNewMessage(
            previewMsg,
            request,
            scrn,
            user,
            message
    );

    if (group.isPollPostAllowed()) {
      pollDao.createPoll(Arrays.asList(form.getPoll()), form.isMultiSelect(), msgid);
    }

    if (form.getTags() != null) {
      final List<String> tags = TagDao.parseTags(form.getTags());

      tagDao.updateTags(msgid, tags);
      tagDao.updateCounters(Collections.<String>emptyList(), tags);
    }

    userEventsDao.addUserRefEvent(userRefs.toArray(new User[userRefs.size()]), msgid);

    return msgid;
  }

  private boolean updateMessage(Topic oldMsg, Topic msg, User editor, List<String> newTags, String newText) {
    List<String> oldTags = tagDao.getMessageTags(msg.getId());

    EditInfoDto editInfo = new EditInfoDto();

    editInfo.setMsgid(msg.getId());
    editInfo.setEditor(editor.getId());

    boolean modified = false;
    
    String oldText = msgbaseDao.getMessageText(msg.getId()).getText();

    if (!oldText.equals(newText)) {
      editInfo.setOldmessage(oldText);
      modified = true;
      
      msgbaseDao.updateMessage(msg.getId(), newText);
    }

    if (!oldMsg.getTitle().equals(msg.getTitle())) {
      modified = true;
      editInfo.setOldtitle(oldMsg.getTitle());

      namedJdbcTemplate.update(
        "UPDATE topics SET title=:title WHERE id=:id",
        ImmutableMap.of("title", msg.getTitle(), "id", msg.getId())
      );
    }

    if (!equalStrings(oldMsg.getLinktext(), msg.getLinktext())) {
      modified = true;
      editInfo.setOldlinktext(oldMsg.getLinktext());

      namedJdbcTemplate.update(
        "UPDATE topics SET linktext=:linktext WHERE id=:id",
        ImmutableMap.of("linktext", msg.getLinktext(), "id", msg.getId())
      );
    }

    if (!equalStrings(oldMsg.getUrl(), msg.getUrl())) {
      modified = true;
      editInfo.setOldurl(oldMsg.getUrl());

      namedJdbcTemplate.update(
        "UPDATE topics SET url=:url WHERE id=:id",
        ImmutableMap.of("url", msg.getUrl(), "id", msg.getId())
      );
    }

    if (newTags != null) {
      boolean modifiedTags = tagDao.updateTags(msg.getId(), newTags);

      if (modifiedTags) {
        editInfo.setOldtags(TagDao.toString(oldTags));
        tagDao.updateCounters(oldTags, newTags);
        modified = true;
      }
    }

    if (oldMsg.isMinor() != msg.isMinor()) {
      namedJdbcTemplate.update("UPDATE topics SET minor=:minor WHERE id=:id",
              ImmutableMap.of("minor", msg.isMinor(), "id", msg.getId()));
      modified = true;
    }

    if (modified) {
      editInsert.execute(new BeanPropertySqlParameterSource(editInfo));
    }

    return modified;
  }

  private static boolean equalStrings(String s1, String s2) {
    if (Strings.isNullOrEmpty(s1)) {
      return Strings.isNullOrEmpty(s2);
    }

    return s1.equals(s2);
  }

  private boolean updatePoll(Topic message, List<PollVariant> newVariants, boolean multiselect) throws PollNotFoundException {
    boolean modified = false;

    final Poll poll = pollDao.getPollByTopicId(message.getId());

    ImmutableList<PollVariant> oldVariants = poll.getVariants();

    Map<Integer, String> newMap = PollVariant.toMap(newVariants);

    for (final PollVariant var : oldVariants) {
      final String label = newMap.get(var.getId());

      if (!equalStrings(var.getLabel(), label)) {
        modified = true;
      }

      if (Strings.isNullOrEmpty(label)) {
        pollDao.removeVariant(var);
      } else {
        pollDao.updateVariant(var, label);
      }
    }

    for (final PollVariant var : newVariants) {
      if (var.getId()==0 && !Strings.isNullOrEmpty(var.getLabel())) {
        modified = true;

        pollDao.addNewVariant(poll, var.getLabel());
      }
    }

    if (poll.isMultiSelect()!=multiselect) {
      modified = true;
      pollDao.updateMultiselect(poll, multiselect);
    }

    return modified;
  }

  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public boolean updateAndCommit(
          Topic newMsg,
          Topic oldMsg,
          User user,
          List<String> newTags,
          String newText,
          boolean commit,
          Integer changeGroupId,
          int bonus,
          List<PollVariant> pollVariants,
          boolean multiselect
  )  {
    boolean modified = updateMessage(oldMsg, newMsg, user, newTags, newText);

    try {
      if (pollVariants!=null && updatePoll(oldMsg, pollVariants, multiselect)) {
        modified = true;
      }
    } catch (PollNotFoundException e) {
      throw new RuntimeException(e);
    }

    if (commit) {
      if (changeGroupId != null) {
        if (oldMsg.getGroupId() != changeGroupId) {
          jdbcTemplate.update("UPDATE topics SET groupid=? WHERE id=?", changeGroupId, oldMsg.getId());
          jdbcTemplate.update("UPDATE groups SET stat4=stat4+1 WHERE id=? or id=?", oldMsg.getGroupId(), changeGroupId);
        }
      }

      commit(oldMsg, user, bonus);
    }

    if (modified) {
      logger.info("сообщение " + oldMsg.getId() + " исправлено " + user.getNick());
    }

    return modified;
  }

  private void commit(Topic msg, User commiter, int bonus) {
    if (bonus < 0 || bonus > 20) {
      throw new IllegalStateException("Неверное значение bonus");
    }

    jdbcTemplate.update(
            "UPDATE topics SET moderate='t', commitby=?, commitdate='now' WHERE id=?",
            commiter.getId(),
            msg.getId()
    );

    User author;
    try {
      author = userDao.getUser(msg.getUid());
    } catch (UserNotFoundException e) {
      throw new RuntimeException(e);
    }

    userDao.changeScore(author.getId(), bonus);
  }

  public void uncommit(Topic msg) {
    jdbcTemplate.update("UPDATE topics SET moderate='f',commitby=NULL,commitdate=NULL WHERE id=?", msg.getId());
  }

  public Topic getPreviousMessage(Topic message, User currentUser) {
    if (message.isSticky()) {
      return null;
    }

    SectionScrollModeEnum sectionScrollMode;

    try {
      sectionScrollMode = sectionService.getScrollMode(message.getSectionId());
    } catch (SectionNotFoundException e) {
      logger.error(e);
      return null;
    }

    List<Integer> res;

    switch (sectionScrollMode) {
      case SECTION:
        res = jdbcTemplate.queryForList(
                "SELECT topics.id as msgid " +
                        "FROM topics " +
                        "WHERE topics.commitdate=(SELECT max(commitdate) FROM topics, groups, sections WHERE sections.id=groups.section AND topics.commitdate<? AND topics.groupid=groups.id AND groups.section=? AND (topics.moderate OR NOT sections.moderate) AND NOT deleted AND not sticky)",
                Integer.class,
                message.getCommitDate(),
                message.getSectionId()
        );
        break;

      case GROUP:
        if (currentUser == null || currentUser.isAnonymous()) {
          res = jdbcTemplate.queryForList(
                  "SELECT max(topics.id) as msgid " +
                          "FROM topics " +
                          "WHERE topics.id<? AND topics.groupid=? AND NOT deleted AND NOT sticky",
                  Integer.class,
                  message.getMessageId(),
                  message.getGroupId()
          );
        } else {
            res = jdbcTemplate.queryForList(
                    "SELECT max(topics.id) as msgid " +
                            "FROM topics " +
                            "WHERE topics.id<? AND topics.groupid=? AND NOT deleted AND NOT sticky " +
                            "AND userid NOT IN (select ignored from ignore_list where userid=?)",
                    Integer.class,
                    message.getMessageId(),
                    message.getGroupId(),
                    currentUser.getId()
            );
        }

        break;

      case NO_SCROLL:
      default:
        return null;
    }

    try {
      if (res.isEmpty() || res.get(0)==null) {
        return null;
      }

      int prevMsgid = res.get(0);

      return getById(prevMsgid);
    } catch (MessageNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public Topic getNextMessage(Topic message, User currentUser) {
    if (message.isSticky()) {
      return null;
    }

    SectionScrollModeEnum sectionScrollMode;

    try {
      sectionScrollMode = sectionService.getScrollMode(message.getSectionId());
    } catch (SectionNotFoundException e) {
      logger.error(e);
      return null;
    }

    List<Integer> res;

    switch (sectionScrollMode) {
      case SECTION:
        res = jdbcTemplate.queryForList(
                "SELECT topics.id as msgid FROM topics WHERE topics.commitdate=(SELECT min(commitdate) FROM topics, groups, sections WHERE sections.id=groups.section AND topics.commitdate>? AND topics.groupid=groups.id AND groups.section=? AND (topics.moderate OR NOT sections.moderate) AND NOT deleted)",
                Integer.class,
                message.getCommitDate(),
                message.getSectionId()
        );
        break;

      case GROUP:
        if (currentUser == null || currentUser.isAnonymous()) {
          res = jdbcTemplate.queryForList(
                  "SELECT min(topics.id) as msgid " +
                          "FROM topics " +
                          "WHERE topics.id>? AND topics.groupid=? AND NOT deleted",
                  Integer.class,
                  message.getId(),
                  message.getGroupId()
          );
        } else {
          res = jdbcTemplate.queryForList(
                  "SELECT min(topics.id) as msgid " +
                          "FROM topics " +
                          "WHERE topics.id>? AND topics.groupid=? AND NOT deleted " +
                          "AND userid NOT IN (select ignored from ignore_list where userid=?)",
                  Integer.class,
                  message.getId(),
                  message.getGroupId(),
                  currentUser.getId()
          );
        }
        break;

      case NO_SCROLL:
      default:
        return null;
    }

    try {
      if (res.isEmpty() || res.get(0)==null) {
        return null;
      }

      int nextMsgid = res.get(0);

      return getById(nextMsgid);
    } catch (MessageNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public List<EditInfoDto> loadEditInfo(int msgid)  {
    List<EditInfoDto> list = jdbcTemplate.query(
      "SELECT * FROM edit_info WHERE msgid=? ORDER BY id DESC",
      BeanPropertyRowMapper.newInstance(EditInfoDto.class),
      msgid
    );

    return ImmutableList.copyOf(list);
  }

  public void resolveMessage(int msgid, boolean b) {
    jdbcTemplate.update(
            "UPDATE topics SET resolved=?,lastmod=lastmod+'1 second'::interval WHERE id=?",
            b,
            msgid
    );
  }

  public void setTopicOptions(Topic msg, int postscore, boolean sticky, boolean notop, boolean minor) {
    jdbcTemplate.update(
            "UPDATE topics SET postscore=?, sticky=?, notop=?, lastmod=CURRENT_TIMESTAMP,minor=? WHERE id=?",
            postscore,
            sticky,
            notop,
            minor,
            msg.getId()
    );
  }

  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void moveTopic(Topic msg, Group newGrp, User moveBy) {
    String url = msg.getUrl();
    
    boolean lorcode = msgbaseDao.getMessageText(msg.getId()).isLorcode();

    jdbcTemplate.update("UPDATE topics SET groupid=?,lastmod=CURRENT_TIMESTAMP WHERE id=?", newGrp.getId(), msg.getId());

    if (!newGrp.isLinksAllowed() && !newGrp.isImagePostAllowed()) {
      jdbcTemplate.update("UPDATE topics SET linktext=null, url=null WHERE id=?", msg.getId());

      String title = msg.getGroupUrl();
      String linktext = msg.getLinktext();

      /* if url is not null, update the topic text */
      String link;

      if (!Strings.isNullOrEmpty(url)) {
        if (lorcode) {
          link = "\n[url=" + url + ']' + linktext + "[/url]\n";
        } else {
          link = "<br><a href=\"" + url + "\">" + linktext + "</a>\n<br>\n";
        }
      } else {
        link = "";
      }

      String add;

      if (lorcode) {
        add = '\n' + link + "\n[i]Перемещено " + moveBy.getNick() + " из " + title + "[/i]\n";
      } else {
        add = '\n' + link + "<br><i>Перемещено " + moveBy.getNick() + " из " + title + "</i>\n";
      }

      msgbaseDao.appendMessage(msg.getId(), add);
    }

    if (!newGrp.isModerated()) {
      ImmutableList<String> oldTags = tagDao.getMessageTags(msg.getId());
      tagDao.updateTags(msg.getId(), ImmutableList.<String>of());
      tagDao.updateCounters(oldTags, Collections.<String>emptyList());
    }
  }
}
