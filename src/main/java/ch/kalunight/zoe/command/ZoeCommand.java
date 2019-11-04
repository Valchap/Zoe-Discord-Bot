package ch.kalunight.zoe.command;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;

public abstract class ZoeCommand extends Command {

  private static final AtomicInteger commandExecuted = new AtomicInteger(0);
  private static final AtomicInteger commandFinishedCorrectly = new AtomicInteger(0);
  private static final AtomicInteger commandFinishedWithError = new AtomicInteger(0);

  private static final Logger logger = LoggerFactory.getLogger(ZoeCommand.class);
  
  @Override
  protected void execute(CommandEvent event) {
    logger.info("Command \"{}\" executed", this.getClass().getName());
    commandExecuted.incrementAndGet();
    try {
      executeCommand(event);
    } catch (Exception e) {
      logger.error("Unexpected exception in {} commands. Error : {}", this.getClass().getName(), e.getMessage(), e);
      commandFinishedWithError.incrementAndGet();
      return;
    }
    logger.info("Command \"{}\" finished correctly", this.getClass().getName());
    commandFinishedCorrectly.incrementAndGet();
  }
  
  protected abstract void executeCommand(CommandEvent event);

  public static void clearStats() {
    commandExecuted.set(0);
    commandFinishedCorrectly.set(0);
    commandFinishedWithError.set(0);
  }
  
  public static AtomicInteger getCommandExecuted() {
    return commandExecuted;
  }

  public static AtomicInteger getCommandFinishedCorrectly() {
    return commandFinishedCorrectly;
  }

  public static AtomicInteger getCommandFinishedWithError() {
    return commandFinishedWithError;
  }

}
