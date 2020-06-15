package ru.hostco.dvs.gitlabtelegramintegration.service;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.hostco.dvs.gitlabtelegramintegration.config.ApplicationConfiguration;
import ru.hostco.dvs.gitlabtelegramintegration.dto.GitLabUser;
import ru.hostco.dvs.gitlabtelegramintegration.dto.MergeRequestInfo;

@Slf4j
@Service
public class Syncer {

  @Autowired
  public Syncer(
      @NonNull final GitLabClient gitLabClient,
      @NonNull final ApplicationConfiguration applicationConfiguration) {
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
        });

    log.info("mergeRequests");

  }
}
