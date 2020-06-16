package ru.hostco.dvs.gitlabtelegramintegration.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.hostco.dvs.gitlabtelegramintegration.config.ApplicationConfiguration;
import ru.hostco.dvs.gitlabtelegramintegration.dto.gitlab.GitLabUser;
import ru.hostco.dvs.gitlabtelegramintegration.dto.gitlab.MergeRequestInfo;
import ru.hostco.dvs.gitlabtelegramintegration.dto.telegram.Message;
import ru.hostco.dvs.gitlabtelegramintegration.dto.telegram.Response;
import ru.hostco.dvs.gitlabtelegramintegration.dto.telegram.Update;
import ru.hostco.dvs.gitlabtelegramintegration.jpa.models.GitlabTelegramUserMapping;
import ru.hostco.dvs.gitlabtelegramintegration.jpa.models.UpdateOffset;
import ru.hostco.dvs.gitlabtelegramintegration.jpa.repositories.GitlabTelegramUserMappingRepository;
import ru.hostco.dvs.gitlabtelegramintegration.jpa.repositories.UpdateOffsetRepository;

@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = {@Autowired})
public class Syncer {

  /**
   * Конфигурация
   */
  private final ApplicationConfiguration applicationConfiguration;
  /**
   * Клиент для гитлаба
   */
  private final GitLabClient gitLabClient;
  /**
   * Клиент для телеграмма
   */
  private final TelegramBotClient telegramBotClient;
  /**
   * Репозиторий update'ов
   */
  private final UpdateOffsetRepository updateOffsetRepository;
  /**
   * Репозиторий маппингов
   */
  private final GitlabTelegramUserMappingRepository gitlabTelegramUserMappingRepository;

  @Scheduled(fixedDelay = 6 * 1000, initialDelay = 1 * 1000)
  public void updateUsers() {

    final Long updateOffset = updateOffsetRepository
        .findAllOrderByDateInsertDesc(PageRequest.of(0, 1, Sort.by("dateInsert").descending()))
        .stream().map(UpdateOffset::getUpdateId).filter(Objects::nonNull).map(u -> u + 1)
        .findFirst().orElse(null);
    /**
     * Сообщения от телеграмма
     */
    final Response<Collection<Update>> updates = telegramBotClient
        .getUpdates(applicationConfiguration.getTelegramBotToken(), updateOffset);

    if (updates.getResult().isEmpty()) {
      return;
    }
    final Long lastGotUpdateId = updates.getResult().stream().map(Update::getUpdateId)
        .filter(Objects::nonNull).sorted(Comparator.reverseOrder()).findFirst().get();

    final List<Message> telegramToGitlabUsernames = updates.getResult().stream()
        .map(Update::getMessage).filter(Objects::nonNull)
        .filter(m -> m.getText().matches("/gitlabusername \\w+")).collect(Collectors.toList());

    telegramToGitlabUsernames.stream().forEach(t -> {
      final Page<GitlabTelegramUserMapping> databaseUsernameMapping = gitlabTelegramUserMappingRepository
          .findAllByTelegramUsernameEqualsIgnoreCase(t.getFrom().getUsername(), Pageable.unpaged());
      gitlabTelegramUserMappingRepository.deleteAll(databaseUsernameMapping);

      final String gitlabUsername = t.getText().replaceAll("/gitlabusername\\s+", "");

      final GitlabTelegramUserMapping newMapping = GitlabTelegramUserMapping.builder()
          .telegramUsername(t.getFrom().getUsername())
          .telegramChatId(t.getChat().getId())
          .telegramFirstName(t.getFrom().getFirstName())
          .telegramLastName(t.getFrom().getLastName())
          .gitlabUsername(gitlabUsername)
          .build();

      gitlabTelegramUserMappingRepository.save(newMapping);
    });

    final UpdateOffset newUpdateOffset = UpdateOffset.builder().updateId(lastGotUpdateId).build();
    updateOffsetRepository.save(newUpdateOffset);

    log.info("telegram part completed");
  }

  //TODO Вынести в конфиг
  @Scheduled(fixedDelay = 6 * 1000, initialDelay = 1 * 1000)//(cron = "* 0/15 * * * *")
  public void assigneeMergeRequests() {
    /**
     * Приватный токен пользователя гитлаба из конфига
     */
    final String gitlabPrivateToken = applicationConfiguration.getGitlabPrivateToken();
    /**
     * Информация о текущем пользователе
     */
    final GitLabUser currentUserInfo = gitLabClient.getCurrentUserInfo(gitlabPrivateToken);
    /**
     * Получаем все мердж реквесты, назначенные на текущего пользователя
     */
    final Collection<MergeRequestInfo> mergeRequestInfos = gitLabClient
        .getMergeRequests(gitlabPrivateToken, "assigned_to_me");
    /**
     * Отфильтровываем те, кто ещё никто не смерджил
     */
    mergeRequestInfos.stream().filter(m -> m.getMergedBy() == null)
        .forEach(m -> {
          /**
           * Получаем всех участников проекта(репозитория)
           */
          final Collection<GitLabUser> gitLabUsers = gitLabClient
              .getProjectMembers(gitlabPrivateToken, m.getProjectId());
          /**
           * Отфильтровываем среди пользователей тех, на кого можно назначить МР
           */
          final List<GitLabUser> usersCanBeAssigned = gitLabUsers.stream()
              .filter(u -> !currentUserInfo.equals(u) && !u.equals(m.getAuthor())
                  && u.getAccessLevel() >= 30).collect(Collectors.toList());
          /**
           * Выбираем человека, на которого назначим МР
           */
          final GitLabUser newAssignee = usersCanBeAssigned.stream()
              .skip((int) (usersCanBeAssigned.size() * Math.random()))
              .findAny().get();
          /**
           * Назначаем на выбранного участника проекта(не на себя и не на автора)
           */
          gitLabClient
              .changeMergeRequestAssignee(gitlabPrivateToken, m.getProjectId(), m.getIid(),
                  newAssignee.getId());

          /**
           * Отправляем уведомление и ссылку на merge request
           */
          final Page<GitlabTelegramUserMapping> notifyUsers = gitlabTelegramUserMappingRepository
              .findAllByGitlabUsernameEqualsIgnoreCase(newAssignee.getUsername(),
                  Pageable.unpaged());

          notifyUsers.forEach(u -> {
            final StringBuilder text = new StringBuilder()
                .append(u.getTelegramUsername())
                .append(", на вас назначен МР: \n")
                .append(m.getWebUrl());

            if (m.getSourceBranch().matches("(feature)|(hotfix)/\\d+")) {
              final String taskNumber = m.getSourceBranch().replaceAll("(feature)|(hotfix)/", "");
              if (taskNumber != null) {
                text.append("\nhttps://sd.hostco.ru/issues/")
                    .append(taskNumber);
              }
            }

            telegramBotClient
                .sendMessage(applicationConfiguration.getTelegramBotToken(), u.getTelegramChatId(),
                    text.toString());

          });
        });

    log.info("gitlab part completed");
  }
}
