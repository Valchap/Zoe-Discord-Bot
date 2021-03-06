package ch.kalunight.zoe.service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jagrosh.jdautilities.command.CommandEvent;
import ch.kalunight.zoe.ServerData;
import ch.kalunight.zoe.Zoe;
import ch.kalunight.zoe.exception.NoValueRankException;
import ch.kalunight.zoe.model.ComparableMessage;
import ch.kalunight.zoe.model.GameQueueConfigId;
import ch.kalunight.zoe.model.dto.DTO;
import ch.kalunight.zoe.model.dto.GameInfoCardStatus;
import ch.kalunight.zoe.model.dto.DTO.InfoChannel;
import ch.kalunight.zoe.model.dto.DTO.InfoPanelMessage;
import ch.kalunight.zoe.model.dto.DTO.LastRank;
import ch.kalunight.zoe.model.dto.DTO.LeagueAccount;
import ch.kalunight.zoe.model.dto.DTO.Player;
import ch.kalunight.zoe.model.dto.DTO.RankHistoryChannel;
import ch.kalunight.zoe.model.player_data.FullTier;
import ch.kalunight.zoe.model.player_data.Team;
import ch.kalunight.zoe.repositories.ConfigRepository;
import ch.kalunight.zoe.repositories.CurrentGameInfoRepository;
import ch.kalunight.zoe.repositories.GameInfoCardRepository;
import ch.kalunight.zoe.repositories.InfoChannelRepository;
import ch.kalunight.zoe.repositories.LastRankRepository;
import ch.kalunight.zoe.repositories.LeagueAccountRepository;
import ch.kalunight.zoe.repositories.PlayerRepository;
import ch.kalunight.zoe.repositories.RankHistoryChannelRepository;
import ch.kalunight.zoe.repositories.ServerRepository;
import ch.kalunight.zoe.repositories.ServerStatusRepository;
import ch.kalunight.zoe.repositories.TeamRepository;
import ch.kalunight.zoe.translation.LanguageManager;
import ch.kalunight.zoe.util.FullTierUtil;
import ch.kalunight.zoe.util.InfoPanelRefresherUtil;
import ch.kalunight.zoe.util.request.RiotRequest;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageReaction;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.rithms.riot.api.RiotApiException;
import net.rithms.riot.api.endpoints.league.dto.LeagueEntry;
import net.rithms.riot.api.endpoints.spectator.dto.CurrentGameInfo;
import net.rithms.riot.constant.Platform;

public class InfoPanelRefresher implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(InfoPanelRefresher.class);

  private static final AtomicLong nbrServerRefreshedLast2Minutes = new AtomicLong(0);

  private DTO.Server server;

  private TextChannel infochannel;

  private RankHistoryChannel rankChannel;

  private Guild guild;

  private boolean needToWait = false;

  public InfoPanelRefresher(DTO.Server server) {
    this.server = server;
    guild = Zoe.getJda().getGuildById(server.serv_guildId);
  }

  public InfoPanelRefresher(DTO.Server server, boolean needToWait) {
    this.server = server;
    this.needToWait = needToWait;
    guild = Zoe.getJda().getGuildById(server.serv_guildId);
  }

  private class CurrentGameWithRegion {
    public DTO.CurrentGameInfo currentGameInfo;
    public Platform platform;

    public CurrentGameWithRegion(DTO.CurrentGameInfo currentGameInfo, Platform platform) {
      this.currentGameInfo = currentGameInfo;
      this.platform = platform;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + getEnclosingInstance().hashCode();
      result = prime * result + ((currentGameInfo == null) ? 0 : currentGameInfo.hashCode());
      result = prime * result + ((platform == null) ? 0 : platform.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if(this == obj)
        return true;
      if(obj == null)
        return false;
      if(getClass() != obj.getClass())
        return false;
      CurrentGameWithRegion other = (CurrentGameWithRegion) obj;
      if(!getEnclosingInstance().equals(other.getEnclosingInstance()))
        return false;
      if(currentGameInfo == null) {
        if(other.currentGameInfo != null)
          return false;
      } else if(currentGameInfo.currentgame_currentgame.getGameId() != other.currentGameInfo.currentgame_currentgame.getGameId())
        return false;
      if(platform != other.platform)
        return false;
      return true;
    }

    private InfoPanelRefresher getEnclosingInstance() {
      return InfoPanelRefresher.this;
    }
  }

  @Override
  public void run() {
    try {

      nbrServerRefreshedLast2Minutes.incrementAndGet();

      DTO.InfoChannel infoChannelDTO = InfoChannelRepository.getInfoChannel(server.serv_guildId);
      if(infoChannelDTO != null && guild != null) {
        infochannel = guild.getTextChannelById(infoChannelDTO.infochannel_channelid);
      }


      List<DTO.Player> playersDTO = PlayerRepository.getPlayers(server.serv_guildId);

      if(needToWait) {
        TimeUnit.SECONDS.sleep(5);
      }

      if(infochannel != null) {
        cleanOldInfoChannelMessage();
      }

      rankChannel = RankHistoryChannelRepository.getRankHistoryChannel(server.serv_guildId);

      if(infochannel != null) {
        refreshAllLeagueAccountCurrentGamesAndDeleteOlderInfoCard(playersDTO);
      }

      if(infochannel != null && guild != null) {

        cleanUnlinkInfoCardAndCurrentGame();
        cleanRegisteredPlayerNoLongerInGuild(playersDTO);
        refreshGameCardStatus();

        refreshInfoPanel(infoChannelDTO);

        if(ConfigRepository.getServerConfiguration(server.serv_guildId).getInfoCardsOption().isOptionActivated()) {
          createMissingInfoCard();
          treathGameCardWithStatus();
        }

        cleanInfoChannel();
        clearLoadingEmote();
      }else {
        InfoChannelRepository.deleteInfoChannel(server);
      }
    }catch (InsufficientPermissionException e) {
      logger.debug("Permission {} missing for infochannel in the guild {}, try to autofix the issue... (Low chance to work)",
          e.getPermission().getName(), guild.getName());
      try {
        PermissionOverride permissionOverride = infochannel
            .putPermissionOverride(guild.getMember(Zoe.getJda().getSelfUser())).complete();

        permissionOverride.getManager().grant(e.getPermission()).complete();
        logger.debug("Autofix complete !");
      }catch(Exception e1) {
        logger.debug("Autofix fail ! Error message : {} ", e1.getMessage());
      }

    } catch (SQLException e) {
      logger.error("SQL Exception when refresh the infopanel !", e);
    } catch(Exception e) {
      logger.error("The thread got a unexpected error (The channel got probably deleted when the refresh append)", e);
    } finally {
      try {
        ServerRepository.updateTimeStamp(server.serv_guildId, LocalDateTime.now());
        ServerStatusRepository.updateInTreatment(ServerStatusRepository.getServerStatus(server.serv_guildId).servstatus_id, false);
      } catch(SQLException e) {
        logger.error("SQL Exception when updating timeStamp and treatment !", e);
      }
    }
  }

  private void cleanInfoChannel() {
    try {
      cleaningInfoChannel();
    }catch (InsufficientPermissionException e) {
      logger.info("Error in a infochannel when cleaning : {}", e.getMessage());

    }catch (Exception e) {
      logger.warn("An unexpected error when cleaning info channel has occure.", e);
    }
  }

  private void cleanUnlinkInfoCardAndCurrentGame() throws SQLException {
    List<DTO.CurrentGameInfo> currentGamesInfo = CurrentGameInfoRepository.getCurrentGamesWithoutLinkAccounts(server.serv_guildId);

    for(DTO.CurrentGameInfo currentGame : currentGamesInfo) {
      DTO.GameInfoCard gameCard = GameInfoCardRepository.getGameInfoCardsWithCurrentGameId(server.serv_guildId, currentGame.currentgame_id);

      if(gameCard.gamecard_infocardmessageid != 0) {
        retrieveAndRemoveMessage(gameCard.gamecard_infocardmessageid);
        retrieveAndRemoveMessage(gameCard.gamecard_titlemessageid);
      }
      GameInfoCardRepository.deleteGameInfoCardsWithId(gameCard.gamecard_id);
      CurrentGameInfoRepository.deleteCurrentGame(currentGame, server);
    }
  }

  private void treathGameCardWithStatus() throws SQLException {

    List<DTO.GameInfoCard> gameInfoCards = GameInfoCardRepository.getGameInfoCards(server.serv_guildId);

    for(DTO.GameInfoCard gameInfoCard : gameInfoCards) {
      switch(gameInfoCard.gamecard_status) {
      case IN_CREATION:
        GameInfoCardRepository.updateGameInfoCardStatusWithId(gameInfoCard.gamecard_id, GameInfoCardStatus.IN_TREATMENT);

        List<DTO.LeagueAccount> accountsLinked = LeagueAccountRepository
            .getLeaguesAccountsWithGameCardsId(gameInfoCard.gamecard_id);

        DTO.LeagueAccount account = accountsLinked.get(0);

        DTO.CurrentGameInfo currentGame = CurrentGameInfoRepository.getCurrentGameWithLeagueAccountID(account.leagueAccount_id);

        ServerData.getInfocardsGenerator().execute(
            new InfoCardsWorker(server, infochannel, accountsLinked.get(0), currentGame, gameInfoCard));
        break;
      case IN_WAIT_OF_DELETING:
        GameInfoCardRepository.deleteGameInfoCardsWithId(gameInfoCard.gamecard_id);
        deleteDiscordInfoCard(server.serv_guildId, gameInfoCard);
        break;
      default:
        break;
      }
    }
  }

  private void refreshInfoPanel(DTO.InfoChannel infoChannelDTO) throws SQLException {
    ArrayList<String> infoPanels = CommandEvent.splitMessage(refreshPannel());

    List<DTO.InfoPanelMessage> infoPanelMessages = InfoChannelRepository.getInfoPanelMessages(server.serv_guildId);

    checkMessageDisplaySync(infoPanelMessages, infoChannelDTO); 
    
    infoPanelMessages = InfoChannelRepository.getInfoPanelMessages(server.serv_guildId);

    if(infoPanels.size() < infoPanelMessages.size()) {
      int nbrMessageToDelete = infoPanelMessages.size() - infoPanels.size();
      for(int i = 0; i < nbrMessageToDelete; i++) {
        DTO.InfoPanelMessage infoPanelMessage = infoPanelMessages.get(i);
        Message message = infochannel.retrieveMessageById(infoPanelMessage.infopanel_messageId).complete();
        message.delete().queue();
        InfoChannelRepository.deleteInfoPanelMessage(infoPanelMessage.infopanel_id);
      }

    } else {
      int nbrMessageToAdd = infoPanels.size() - infoPanelMessages.size();
      for(int i = 0; i < nbrMessageToAdd; i++) {
        Message message = infochannel.sendMessage(LanguageManager.getText(server.serv_language, "loading")).complete();
        InfoChannelRepository.createInfoPanelMessage(infoChannelDTO.infoChannel_id, message.getIdLong());
      }
    }

    infoPanelMessages.clear();
    infoPanelMessages.addAll(InfoChannelRepository.getInfoPanelMessages(server.serv_guildId));

    infoPanelMessages = orderInfoPanelMessagesByTime(infoPanelMessages);
    
    for(int i = 0; i < infoPanels.size(); i++) {
      DTO.InfoPanelMessage infoPanel = infoPanelMessages.get(i);
      infochannel.retrieveMessageById(infoPanel.infopanel_messageId).complete().editMessage(infoPanels.get(i)).queue();
    }
  }

  private void checkMessageDisplaySync(List<InfoPanelMessage> infoPanelMessages, InfoChannel infochannelDTO) {

    List<Message> messagesToCheck = new ArrayList<>();
    for(InfoPanelMessage messageToLoad : infoPanelMessages) {
      Message message = infochannel.retrieveMessageById(messageToLoad.infopanel_messageId).complete();
      messagesToCheck.add(message);
    }

    boolean needToResend = false;

    List<Message> orderedMessage = orderMessagesByTime(messagesToCheck);

    for(Message messageToCompare : orderedMessage) {
      for(Message secondMessageToCompare : orderedMessage) {
        if(messageToCompare.getIdLong() != secondMessageToCompare.getIdLong()) {
          if(messageToCompare.getTimeCreated().until(secondMessageToCompare.getTimeCreated(), ChronoUnit.MINUTES) > 5) {
            needToResend = true;
          }
        }
      }
    }

    if(needToResend) {
      int messageNeeded = infoPanelMessages.size();
      
      for(InfoPanelMessage infoPanelMessageToDelete : infoPanelMessages) {
        Message messageToDelete = infochannel.retrieveMessageById(infoPanelMessageToDelete.infopanel_messageId).complete();

        try {
          InfoChannelRepository.deleteInfoPanelMessage(infoPanelMessageToDelete.infopanel_id);
          messageToDelete.delete().queue();
        } catch (SQLException e) {
          logger.warn("Error when deleting infoPanel message in db. Try again next refresh");
        }
      }

      for(int i = 0; i < messageNeeded; i++) {
        Message message = infochannel.sendMessage("**" + LanguageManager.getText(server.serv_language, "infopanelMessageReSendMessage") + "**").complete();
        try {
          InfoChannelRepository.createInfoPanelMessage(infochannelDTO.infoChannel_id, message.getIdLong());
        } catch (SQLException e) {
          logger.warn("Error while creating new info Panel Message. Try again in the process.");
        }
      }
    }
  }

  private List<Message> orderMessagesByTime(List<Message> messagesToCheck) {

    List<ComparableMessage> messagesToOrder = new ArrayList<>();

    for(Message message : messagesToCheck) {
      messagesToOrder.add(new ComparableMessage(message));
    }
    
    Collections.sort(messagesToOrder); 
    
    List<Message> messagesOrdered = new ArrayList<>();
    for(ComparableMessage messageOrdered : messagesToOrder) {
      messagesOrdered.add(messageOrdered.getMessage());
    }
    return messagesOrdered;
  }
  
  private List<InfoPanelMessage> orderInfoPanelMessagesByTime(List<InfoPanelMessage> infoPanelMessages) {
    
    List<Message> baseMessageToOrder = new ArrayList<>();
    for(InfoPanelMessage infoPanelMessage : infoPanelMessages) {
      baseMessageToOrder.add(infochannel.retrieveMessageById(infoPanelMessage.infopanel_messageId).complete());
    }
    
    List<ComparableMessage> messagesToOrder = new ArrayList<>();

    for(Message message : baseMessageToOrder) {
      messagesToOrder.add(new ComparableMessage(message));
    }
    
    Collections.sort(messagesToOrder);
    
    List<InfoPanelMessage> messagesOrdered = new ArrayList<>();
    for(ComparableMessage messageOrdered : messagesToOrder) {
      for(InfoPanelMessage infoPanelMessage : infoPanelMessages) {
        if(messageOrdered.getMessage().getIdLong() == infoPanelMessage.infopanel_messageId) {
          messagesOrdered.add(infoPanelMessage);
        }
      }
    }

    return messagesOrdered;
  }

  private List<DTO.GameInfoCard> refreshGameCardStatus() throws SQLException {
    List<DTO.GameInfoCard> gameInfoCards = GameInfoCardRepository.getGameInfoCards(server.serv_guildId);

    for(DTO.GameInfoCard gameInfoCard : gameInfoCards) {
      if(gameInfoCard.gamecard_status == GameInfoCardStatus.IN_WAIT_OF_MATCH_ENDING) {
        if(gameInfoCard.gamecard_fk_currentgame == 0) {
          GameInfoCardRepository.updateGameInfoCardStatusWithId(
              gameInfoCard.gamecard_id, GameInfoCardStatus.IN_WAIT_OF_DELETING);
          gameInfoCard.gamecard_status = GameInfoCardStatus.IN_WAIT_OF_DELETING;
        }
      }
    }
    return gameInfoCards;
  }

  private void createMissingInfoCard() throws SQLException {
    List<DTO.CurrentGameInfo> currentGamesWithoutCard = 
        CurrentGameInfoRepository.getCurrentGameWithoutLinkWithGameCardAndWithGuildId(server.serv_guildId);

    List<DTO.CurrentGameInfo> alreadyGeneratedGames = new ArrayList<>();

    DTO.InfoChannel infochannel = InfoChannelRepository.getInfoChannel(server.serv_guildId);

    for(DTO.CurrentGameInfo currentGame : currentGamesWithoutCard) {
      boolean alreadyGenerated = false;
      for(DTO.CurrentGameInfo alreadyGeneratedGame : alreadyGeneratedGames) {
        if(alreadyGeneratedGame.currentgame_currentgame.getGameId() == currentGame.currentgame_currentgame.getGameId()) {
          alreadyGenerated = true;
        }
      }


      if(!alreadyGenerated) {
        GameInfoCardRepository.createGameCards(
            infochannel.infoChannel_id, currentGame.currentgame_id, GameInfoCardStatus.IN_CREATION);
        alreadyGeneratedGames.add(currentGame);
      }


      linkAccountWithGameCard(currentGame);
    }
  }

  private void linkAccountWithGameCard(DTO.CurrentGameInfo currentGame) throws SQLException {
    DTO.GameInfoCard gameCard = GameInfoCardRepository.
        getGameInfoCardsWithCurrentGameId(server.serv_guildId, currentGame.currentgame_id);

    List<DTO.LeagueAccount> leaguesAccountInTheGame = 
        LeagueAccountRepository.getLeaguesAccountsWithCurrentGameId(currentGame.currentgame_id);

    for(DTO.LeagueAccount leagueAccount : leaguesAccountInTheGame) {
      if(leagueAccount != null) {
        LeagueAccountRepository.updateAccountGameCardWithAccountId(leagueAccount.leagueAccount_id, gameCard.gamecard_id);
      }
    }
  }

  private void refreshAllLeagueAccountCurrentGamesAndDeleteOlderInfoCard(List<DTO.Player> playersDTO) throws SQLException {
    List<CurrentGameWithRegion> gameAlreadyAskedToRiot = new ArrayList<>();

    List<DTO.LeagueAccount> allLeaguesAccounts = LeagueAccountRepository.getAllLeaguesAccounts(guild.getIdLong());

    for(DTO.Player player : playersDTO) {
      player.leagueAccounts =
          LeagueAccountRepository.getLeaguesAccounts(server.serv_guildId, player.player_discordId);

      for(DTO.LeagueAccount leagueAccount : player.leagueAccounts) {

        boolean alreadyLoaded = false;
        for(CurrentGameWithRegion currentGameWithRegion : gameAlreadyAskedToRiot) {
          if(leagueAccount.leagueAccount_server.equals(currentGameWithRegion.platform)
              && currentGameWithRegion.currentGameInfo.currentgame_currentgame.
              getParticipantByParticipantId(leagueAccount.leagueAccount_summonerId) != null) {
            LeagueAccountRepository.updateAccountCurrentGameWithAccountId(leagueAccount.leagueAccount_id, 
                currentGameWithRegion.currentGameInfo.currentgame_id);
            alreadyLoaded = true;
          }
        }

        if(alreadyLoaded) {
          continue;
        }

        DTO.CurrentGameInfo currentGameDb = CurrentGameInfoRepository.getCurrentGameWithLeagueAccountID(leagueAccount.leagueAccount_id);

        CurrentGameWithRegion currentGameDbRegion = null;
        if(currentGameDb != null) {
          currentGameDbRegion = 
              new CurrentGameWithRegion(currentGameDb, leagueAccount.leagueAccount_server);
        }

        if(currentGameDb == null || !gameAlreadyAskedToRiot.contains(currentGameDbRegion)) {

          CurrentGameInfo currentGame;
          try {
            currentGame = Zoe.getRiotApi().getActiveGameBySummoner(
                leagueAccount.leagueAccount_server, leagueAccount.leagueAccount_summonerId);
          } catch(RiotApiException e) {
            if(e.getErrorCode() == RiotApiException.DATA_NOT_FOUND) {
              currentGame = null;
            }else {
              continue;
            }
          }

          if(currentGameDb == null && currentGame != null) {
            CurrentGameInfoRepository.createCurrentGame(currentGame, leagueAccount);
          }else if(currentGameDb != null && currentGame != null) {
            if(currentGame.getGameId() == currentGameDb.currentgame_currentgame.getGameId()) {
              CurrentGameInfoRepository.updateCurrentGame(currentGame, leagueAccount);
            }else {
              CurrentGameInfoRepository.deleteCurrentGame(currentGameDb, server);

              searchForRefreshRankChannel(allLeaguesAccounts, currentGameDb);

              CurrentGameInfoRepository.createCurrentGame(currentGame, leagueAccount);
            }
          }else if(currentGameDb != null && currentGame == null) {
            CurrentGameInfoRepository.deleteCurrentGame(currentGameDb, server);

            searchForRefreshRankChannel(allLeaguesAccounts, currentGameDb);
          }
          if(currentGame != null) {
            DTO.CurrentGameInfo currentGameInfo = CurrentGameInfoRepository.getCurrentGameWithLeagueAccountID(leagueAccount.leagueAccount_id);
            gameAlreadyAskedToRiot.add(new CurrentGameWithRegion(currentGameInfo, leagueAccount.leagueAccount_server));
          }
        }
      }
    }
  }

  private void searchForRefreshRankChannel(List<DTO.LeagueAccount> allLeaguesAccounts,
      DTO.CurrentGameInfo currentGameDb) throws SQLException {
    for(DTO.LeagueAccount leagueAccountToCheck : allLeaguesAccounts) {
      if(currentGameDb.currentgame_currentgame.getParticipantByParticipantId(leagueAccountToCheck.leagueAccount_summonerId) != null) {
        Player playerToUpdate = PlayerRepository.getPlayerByLeagueAccountAndGuild(guild.getIdLong(),
            leagueAccountToCheck.leagueAccount_summonerId, leagueAccountToCheck.leagueAccount_server);
        updateRankChannelMessage(playerToUpdate, leagueAccountToCheck, currentGameDb);
      }
    }
  }

  private void updateRankChannelMessage(DTO.Player player, DTO.LeagueAccount leagueAccount, DTO.CurrentGameInfo currentGameDb)
      throws SQLException {
    CurrentGameInfo gameOfTheChange = currentGameDb.currentgame_currentgame;

    LastRank rankBeforeThisGame = LastRankRepository.getLastRankWithLeagueAccountId(leagueAccount.leagueAccount_id);
    if(rankBeforeThisGame == null) {
      LastRankRepository.createLastRank(leagueAccount.leagueAccount_id);
      rankBeforeThisGame = LastRankRepository.getLastRankWithLeagueAccountId(leagueAccount.leagueAccount_id);
    }

    if(gameOfTheChange.getGameQueueConfigId() == GameQueueConfigId.SOLOQ.getId()) {

      LeagueEntry entry = RiotRequest.getLeagueEntrySoloq(leagueAccount.leagueAccount_summonerId, leagueAccount.leagueAccount_server);
      RankedChannelRefresher rankedRefresher = 
          new RankedChannelRefresher(rankChannel, rankBeforeThisGame.lastRank_soloq, entry,
              gameOfTheChange, player, leagueAccount, server);
      ServerData.getRankedMessageGenerator().execute(rankedRefresher);

    }else if(gameOfTheChange.getGameQueueConfigId() == GameQueueConfigId.FLEX.getId()) {

      LeagueEntry entry = RiotRequest.getLeagueEntryFlex(leagueAccount.leagueAccount_summonerId, leagueAccount.leagueAccount_server);
      RankedChannelRefresher rankedRefresher = 
          new RankedChannelRefresher(rankChannel, rankBeforeThisGame.lastRank_flex, entry,
              gameOfTheChange, player, leagueAccount, server);
      ServerData.getRankedMessageGenerator().execute(rankedRefresher);
    }

  }

  private void deleteDiscordInfoCard(long guildId, DTO.GameInfoCard gameCard) throws SQLException {
    List<DTO.LeagueAccount> leaguesAccounts = LeagueAccountRepository.getLeaguesAccountsWithGameCardsId(gameCard.gamecard_id);

    for(DTO.LeagueAccount leagueAccount: leaguesAccounts) {
      LeagueAccountRepository.updateAccountCurrentGameWithAccountId(leagueAccount.leagueAccount_id, 0);
    }

    GameInfoCardRepository.deleteGameInfoCardsWithId(gameCard.gamecard_id);

    retrieveAndRemoveMessage(gameCard.gamecard_infocardmessageid);
    retrieveAndRemoveMessage(gameCard.gamecard_titlemessageid);
  }

  private void retrieveAndRemoveMessage(long messageId) {
    try {
      removeMessage(infochannel.retrieveMessageById(messageId).complete());
    }catch(ErrorResponseException e) {
      logger.info("The wanted info panel message doesn't exist anymore.");
    }
  }

  private void cleanOldInfoChannelMessage() throws SQLException {
    List<DTO.InfoPanelMessage> messagesToRemove = new ArrayList<>();
    List<DTO.InfoPanelMessage> infopanels = InfoChannelRepository.getInfoPanelMessages(server.serv_guildId);
    for(DTO.InfoPanelMessage partInfoPannel : infopanels) {
      try {
        Message message = infochannel.retrieveMessageById(partInfoPannel.infopanel_messageId).complete();
        message.addReaction("U+23F3").complete();
      }catch(ErrorResponseException e) {
        //Try to check in a less pretty way
        try {
          infochannel.retrieveMessageById(partInfoPannel.infopanel_messageId).complete();
        } catch (ErrorResponseException e1) {
          messagesToRemove.add(partInfoPannel);
        }
      }
    }
    for(DTO.InfoPanelMessage messageToRemove : messagesToRemove) {
      InfoChannelRepository.deleteInfoPanelMessage(messageToRemove.infopanel_id);
    }
  }

  private void clearLoadingEmote() throws SQLException {
    for(DTO.InfoPanelMessage messageToClearReaction : InfoChannelRepository.getInfoPanelMessages(server.serv_guildId)) {

      Message retrievedMessage;
      try {
        retrievedMessage = infochannel.retrieveMessageById(messageToClearReaction.infopanel_messageId).complete();
      } catch (ErrorResponseException e) {
        logger.warn("Error when deleting loading emote : {}", e.getMessage(), e);
        continue;
      }

      if(retrievedMessage != null) {
        for(MessageReaction messageReaction : retrievedMessage.getReactions()) {
          try {
            messageReaction.removeReaction(Zoe.getJda().getSelfUser()).queue();
          } catch (ErrorResponseException e) {
            logger.warn("Error when removing reaction : {}", e.getMessage(), e);
          }
        }
      }
    }
  }


  private void cleanRegisteredPlayerNoLongerInGuild(List<DTO.Player> listPlayers) throws SQLException {

    Iterator<DTO.Player> iter = listPlayers.iterator();

    while (iter.hasNext()) {
      DTO.Player player = iter.next();
      try {
        if(guild.retrieveMemberById(player.user.getId()).complete() == null) {
          iter.remove();
          PlayerRepository.updateTeamOfPlayerDefineNull(player.player_id);
        }
      }catch (ErrorResponseException e) {
        if(e.getErrorResponse().equals(ErrorResponse.UNKNOWN_MEMBER)) {
          iter.remove();
          PlayerRepository.updateTeamOfPlayerDefineNull(player.player_id);
        }
      }
    }
  }

  private void cleaningInfoChannel() throws SQLException {

    List<Message> messagesToCheck = infochannel.getIterableHistory().stream()
        .limit(1000)
        .filter(m-> m.getAuthor().getId().equals(Zoe.getJda().getSelfUser().getId()))
        .collect(Collectors.toList());

    List<Message> messagesToDelete = new ArrayList<>();
    List<DTO.InfoPanelMessage> infoPanels = InfoChannelRepository.getInfoPanelMessages(server.serv_guildId);
    List<Long> infoPanelsId = new ArrayList<>();
    for(DTO.InfoPanelMessage infoPanel : infoPanels) {
      infoPanelsId.add(infoPanel.infopanel_messageId);
    }

    for(Message messageToCheck : messagesToCheck) {
      if(!messageToCheck.getTimeCreated().isBefore(OffsetDateTime.now().minusHours(1)) || infoPanelsId.contains(messageToCheck.getIdLong())) {
        continue;
      }

      messagesToDelete.add(messageToCheck);
    }

    if(messagesToDelete.isEmpty()) {
      return;
    }

    if(messagesToDelete.size() > 1) {
      infochannel.purgeMessages(messagesToDelete);
    }else {
      for(Message messageToDelete : messagesToDelete) {
        messageToDelete.delete().queue();
      }
    }
  }

  private void removeMessage(Message message) {
    try {
      if(message != null) {
        message.delete().complete();
      }
    } catch(ErrorResponseException e) {
      if(e.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
        logger.info("Message already deleted");
      } else {
        logger.warn("Unhandle error : {}", e.getMessage());
        throw e;
      }
    }
  }

  private String refreshPannel() throws SQLException {

    final List<Team> teamList = TeamRepository.getAllPlayerInTeams(server.serv_guildId, server.serv_language);
    final StringBuilder stringMessage = new StringBuilder();

    stringMessage.append("__**" + LanguageManager.getText(server.serv_language, "informationPanelTitle") + "**__\n \n");

    for(Team team : teamList) {

      List<DTO.Player> playersList = team.getPlayers();

      if(teamList.size() != 1) {

        int numberOfAccountRanked = 0;
        int totRankValue = 0;

        for(DTO.Player player : playersList) {
          for(DTO.LeagueAccount leagueAccount : player.leagueAccounts) {
            LastRank lastRank = LastRankRepository.getLastRankWithLeagueAccountId(leagueAccount.leagueAccount_id);
            if(lastRank != null && lastRank.lastRank_soloq != null) {
              try {
                totRankValue += new FullTier(lastRank.lastRank_soloq).value();
                numberOfAccountRanked++;
              } catch (NoValueRankException e) {
                //Nothing to do
              }
            }
          }
        }

        if(totRankValue <= 0 || !ConfigRepository.getServerConfiguration(guild.getIdLong()).getInfopanelRankedOption().isOptionActivated()) {
          stringMessage.append("**" + team.getName() + "**\n \n");
        }else {
          FullTier fulltier = new FullTier(totRankValue / numberOfAccountRanked);
          stringMessage.append("**" + team.getName() + "** (" + LanguageManager.getText(server.serv_language, "rankedAvg") + " : " 
              + fulltier.toStringWithoutLp(server.serv_language) + ")\n \n");
        }
      }

      pseudoList(stringMessage, playersList);


      stringMessage.append(" \n");
    }

    stringMessage.append(LanguageManager.getText(server.serv_language, "informationPanelRefreshedTime"));

    return stringMessage.toString();
  }

  private void pseudoList(final StringBuilder stringMessage, List<DTO.Player> playersList) throws SQLException {
    for(DTO.Player player : playersList) {

      List<DTO.LeagueAccount> leagueAccounts = player.leagueAccounts;

      List<DTO.LeagueAccount> accountsInGame = new ArrayList<>();
      List<DTO.LeagueAccount> accountNotInGame = new ArrayList<>();

      for(DTO.LeagueAccount leagueAccount : leagueAccounts) {
        DTO.CurrentGameInfo currentGameInfo = CurrentGameInfoRepository.getCurrentGameWithLeagueAccountID(leagueAccount.leagueAccount_id);
        if(currentGameInfo != null) {
          accountsInGame.add(leagueAccount);
        }else {
          accountNotInGame.add(leagueAccount);
        }
      }

      if(accountsInGame.isEmpty()) {

        if(ConfigRepository.getServerConfiguration(guild.getIdLong()).getInfopanelRankedOption().isOptionActivated()) {

          if(accountNotInGame.size() == 1) {

            LeagueAccount leagueAccount = accountNotInGame.get(0);

            getTextInformationPanelRankOption(stringMessage, player, leagueAccount, false);
          }else {

            stringMessage.append(String.format(LanguageManager.getText(server.serv_language, "infoPanelRankedTitleMultipleAccount"), player.user.getAsMention()) + "\n");

            for(DTO.LeagueAccount leagueAccount : accountNotInGame) {

              getTextInformationPanelRankOption(stringMessage, player, leagueAccount, true);
            }
          }

        } else {
          notInGameWithoutRankInfo(stringMessage, player);
        }
      }else if (accountsInGame.size() == 1) {
        stringMessage.append(player.user.getAsMention() + " : " 
            + InfoPanelRefresherUtil.getCurrentGameInfoStringForOneAccount(accountsInGame.get(0), server.serv_language) + "\n");
      }else {
        stringMessage.append(player.user.getAsMention() + " : " 
            + LanguageManager.getText(server.serv_language, "informationPanelMultipleAccountInGame") + "\n"
            + InfoPanelRefresherUtil.getCurrentGameInfoStringForMultipleAccounts(accountsInGame, server.serv_language));
      }
    }
  }

  private void getTextInformationPanelRankOption(final StringBuilder stringMessage, DTO.Player player,
      DTO.LeagueAccount leagueAccount, boolean mutlipleAccount) throws SQLException {
    LastRank lastRank = LastRankRepository.getLastRankWithLeagueAccountId(leagueAccount.leagueAccount_id);

    if(lastRank == null) {
      Set<LeagueEntry> leaguesEntry;
      try {
        leaguesEntry = Zoe.getRiotApi().getLeagueEntriesBySummonerIdWithRateLimit(leagueAccount.leagueAccount_server, leagueAccount.leagueAccount_summonerId);
      } catch (RiotApiException e) {
        notInGameWithoutRankInfo(stringMessage, player);
        return;
      }

      LeagueEntry soloq = null;
      LeagueEntry flex = null;

      for(LeagueEntry leaguePosition : leaguesEntry) {
        if(leaguePosition.getQueueType().equals("RANKED_SOLO_5x5")) {
          soloq = leaguePosition;
        }else if(leaguePosition.getQueueType().equals("RANKED_FLEX_SR")) {
          flex = leaguePosition;
        }
      }


      if((soloq != null || flex != null) && LastRankRepository.getLastRankWithLeagueAccountId(leagueAccount.leagueAccount_id) == null) {
        LastRankRepository.createLastRank(leagueAccount.leagueAccount_id);


        if(soloq != null) {
          LastRankRepository.updateLastRankSoloqWithLeagueAccountId(soloq, leagueAccount.leagueAccount_id);

        }

        if(flex != null) {
          LastRankRepository.updateLastRankFlexWithLeagueAccountId(flex, leagueAccount.leagueAccount_id);
        }
        lastRank = LastRankRepository.getLastRankWithLeagueAccountId(leagueAccount.leagueAccount_id);
      }


    }

    if(lastRank == null) {
      if(mutlipleAccount) {
        notInGameUnranked(stringMessage, leagueAccount);
      }else {
        notInGameWithoutRankInfo(stringMessage, player);
      }
      return;
    }

    FullTier soloqFullTier;
    FullTier flexFullTier;

    String accountString;
    String baseText;
    if(mutlipleAccount) {
      baseText = "infoPanelRankedTextMultipleAccount";
      accountString = leagueAccount.leagueAccount_name;
    }else {
      baseText = "infoPanelRankedTextOneAccount";
      accountString = player.user.getAsMention();
    }

    if(lastRank.lastRank_soloqLastRefresh != null && lastRank.lastRank_flexLastRefresh == null) {

      soloqFullTier = new FullTier(lastRank.lastRank_soloq);

      stringMessage.append(String.format(LanguageManager.getText(server.serv_language, baseText), accountString, 
          soloqFullTier.toString(server.serv_language), FullTierUtil.getTierRankTextDifference(lastRank.lastRank_soloqSecond, lastRank.lastRank_soloq, server.serv_language)
          + " / " + LanguageManager.getText(server.serv_language, "soloq")) + "\n");
    }else if(lastRank.lastRank_soloqLastRefresh == null && lastRank.lastRank_flexLastRefresh != null) {

      flexFullTier = new FullTier(lastRank.lastRank_flex);

      stringMessage.append(String.format(LanguageManager.getText(server.serv_language, baseText), accountString, 
          flexFullTier.toString(server.serv_language), FullTierUtil.getTierRankTextDifference(lastRank.lastRank_soloqSecond, lastRank.lastRank_soloq, server.serv_language)
          + " / " + LanguageManager.getText(server.serv_language, "flex")) + "\n");
    }else if(lastRank.lastRank_soloqLastRefresh != null && lastRank.lastRank_flexLastRefresh != null) {

      if(lastRank.lastRank_flexLastRefresh.isAfter(lastRank.lastRank_soloqLastRefresh)) {
        flexFullTier = new FullTier(lastRank.lastRank_flex);

        stringMessage.append(String.format(LanguageManager.getText(server.serv_language, baseText), accountString, 
            flexFullTier.toString(server.serv_language), FullTierUtil.getTierRankTextDifference(lastRank.lastRank_soloqSecond, lastRank.lastRank_soloq, server.serv_language)
            + " / " + LanguageManager.getText(server.serv_language, "flex")) + "\n");
      }else {
        soloqFullTier = new FullTier(lastRank.lastRank_soloq);

        stringMessage.append(String.format(LanguageManager.getText(server.serv_language, baseText), accountString, 
            soloqFullTier.toString(server.serv_language), FullTierUtil.getTierRankTextDifference(lastRank.lastRank_soloqSecond, lastRank.lastRank_soloq, server.serv_language)
            + " / " + LanguageManager.getText(server.serv_language, "soloq")) + "\n");
      }
    }else {
      LeagueEntry entrySoloQ = RiotRequest.getLeagueEntrySoloq(leagueAccount.leagueAccount_summonerId, leagueAccount.leagueAccount_server);

      if(entrySoloQ != null) {
        FullTier soloQTier = new FullTier(entrySoloQ);

        stringMessage.append(String.format(LanguageManager.getText(server.serv_language, baseText), accountString, 
            soloQTier.toString(server.serv_language), FullTierUtil.getTierRankTextDifference(lastRank.lastRank_soloqSecond, lastRank.lastRank_soloq, server.serv_language)
            + " / " + LanguageManager.getText(server.serv_language, "soloq")) + "\n");
        LastRankRepository.updateLastRankSoloqWithLeagueAccountId(entrySoloQ, leagueAccount.leagueAccount_id);
      }else {
        if(mutlipleAccount) {
          notInGameUnranked(stringMessage, leagueAccount);
        }else {
          notInGameWithoutRankInfo(stringMessage, player);
        }
      }
    }
  }

  private void notInGameWithoutRankInfo(final StringBuilder stringMessage, DTO.Player player) {
    stringMessage.append(player.user.getAsMention() + " : " 
        + LanguageManager.getText(server.serv_language, "informationPanelNotInGame") + " \n");
  }

  private void notInGameUnranked(final StringBuilder stringMessage, DTO.LeagueAccount leagueAccount) {
    stringMessage.append("- " + leagueAccount.leagueAccount_name + " : " 
        + LanguageManager.getText(server.serv_language, "unranked") + " \n");
  }

  public static AtomicLong getNbrServerSefreshedLast2Minutes() {
    return nbrServerRefreshedLast2Minutes;
  }


}
