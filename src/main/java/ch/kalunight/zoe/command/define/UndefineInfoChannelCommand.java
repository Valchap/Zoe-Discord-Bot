package ch.kalunight.zoe.command.define;

import java.util.function.BiConsumer;

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import ch.kalunight.zoe.ServerData;
import ch.kalunight.zoe.command.ZoeCommand;
import ch.kalunight.zoe.model.ControlPannel;
import ch.kalunight.zoe.model.InfoCard;
import ch.kalunight.zoe.model.Server;
import ch.kalunight.zoe.translation.LanguageManager;
import ch.kalunight.zoe.util.CommandUtil;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;

public class UndefineInfoChannelCommand extends ZoeCommand {

  public UndefineInfoChannelCommand() {
    this.name = "infochannel";
    this.arguments = "";
    Permission[] permissionRequired = {Permission.MANAGE_CHANNEL};
    this.userPermissions = permissionRequired;
    this.help = "undefineInfoChannelHelpMessage";
    this.helpBiConsumer = CommandUtil.getHelpMethodIsChildren(UndefineCommand.USAGE_NAME, name, arguments, help);
  }

  @Override
  protected void executeCommand(CommandEvent event) {
    event.getTextChannel().sendTyping().complete();
    Server server = ServerData.getServers().get(event.getGuild().getId());

    if(server.getInfoChannel() == null) {
      event.reply(LanguageManager.getText(server.getLangage(), "undefineInfoChannelMissingChannel"));
    } else {
      for(InfoCard infoCard : server.getControlePannel().getInfoCards()) {
        infoCard.getMessage().delete().queue();
        infoCard.getTitle().delete().queue();
      }
      for(Message message : server.getControlePannel().getInfoPanel()) {
        message.delete().queue();
      }
      server.setInfoChannel(null);
      server.setControlePannel(new ControlPannel());
      event.reply(LanguageManager.getText(server.getLangage(), "undefineInfoChannelDoneMessage"));
    }
  }

  @Override
  public BiConsumer<CommandEvent, Command> getHelpBiConsumer(CommandEvent event) {
    return helpBiConsumer;
  }
}
