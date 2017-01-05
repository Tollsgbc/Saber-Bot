package io.schedulerbot.core;

import io.schedulerbot.Main;

import io.schedulerbot.core.command.CommandHandler;
import io.schedulerbot.core.schedule.ScheduleManager;
import io.schedulerbot.core.settings.ChannelSettingsManager;
import io.schedulerbot.utils.MessageUtilities;
import io.schedulerbot.utils.VerifyUtilities;
import net.dv8tion.jda.core.MessageHistory;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.guild.GuildJoinEvent;
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.events.ReadyEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;

import java.util.List;
import java.util.function.Consumer;

/**
 * Listens for new messages and performs actions during it's own
 * startup and join/leave guild events.
 */
public class EventListener extends ListenerAdapter
{
    // store the bot botSettings to easy reference
    private String prefix = Main.getBotSettings().getCommandPrefix();
    private String adminPrefix = Main.getBotSettings().getAdminPrefix();
    private String adminId = Main.getBotSettings().getAdminId();
    private int maxEntries = Main.getBotSettings().getMaxEntries();
    private String controlChan = Main.getBotSettings().getControlChan();
    private String scheduleChan = Main.getBotSettings().getScheduleChan();

    private ScheduleManager scheduleManager = Main.scheduleManager;
    private ChannelSettingsManager channelSettingsManager = Main.CHANNEL_SETTINGS_MANAGER;
    private CommandHandler cmdHandler = Main.commandHandler;

    @Override
    public void onMessageReceived(MessageReceivedEvent event)
    {
        // store some properties of the message for use later
        String content = event.getMessage().getContent();   // the raw string the user sent
        String userId = event.getAuthor().getId();          // the ID of the user
        String origin = event.getChannel().getName();       // the name of the originating text channel

        if (event.isFromType(ChannelType.PRIVATE))
        {
            if (content.startsWith(prefix + "help") || content.startsWith(prefix + "setup"))
            {
                cmdHandler.handleCommand(event, 0);
                return;
            }
            else if (content.startsWith(adminPrefix) && userId.equals(adminId))
            {
                cmdHandler.handleCommand(event, 1);
                return;
            }
            return;
        }

        // if main schedule channel is not setup go no further
        if( !VerifyUtilities.verifyScheduleChannel( event.getGuild() ) )
        {
           return;
        }

        if (origin.equals(controlChan) && content.startsWith(prefix))
        {
            // generate botSettings for the channel if none yet
            channelSettingsManager.checkChannel( event.getChannel() );

            // handle command received
            cmdHandler.handleCommand(event, 0);
            return;
        }

        if (origin.equals(scheduleChan))
        {
            // delete other user's messages
            if (!userId.equals(Main.getBotSelfUser().getId()))
                MessageUtilities.deleteMsg(event.getMessage(), null);

            // if it is from myself, resend the guild botSettings message (so that it is at the bottom)
            else
                channelSettingsManager.sendSettingsMsg(event.getChannel());
        }
    }

    @Override
    public void onReady(ReadyEvent event)
    {
        // loads schedules and botSettings for every connected guild
        for (Guild guild : event.getJDA().getGuilds())
        {
            List<TextChannel> chan = guild.getTextChannelsByName(scheduleChan, false);
            if (!chan.isEmpty())
            {
                // create a message history object
                MessageHistory history = chan.get(0).getHistory();

                // ready a consumer to parse the history
                Consumer<List<Message>> cons = (l) -> {
                    for (Message message : l)
                    {
                        if (message.getAuthor().getId().equals(Main.getBotSelfUser().getId()))
                        {
                            if (message.getRawContent().startsWith("```java"))
                                channelSettingsManager.loadSettings( message );
                            else
                                scheduleManager.addEntry(message);
                        }
                        else
                            MessageUtilities.deleteMsg( message, null );
                    }

                    channelSettingsManager.checkChannel(chan.get(0));
                };

                // retrieve history and have the consumer act on it
                history.retrievePast((maxEntries>=0) ? maxEntries*2:50).queue(cons);
            }
        }
    }

    @Override
    public void onGuildJoin( GuildJoinEvent event )
    {
        Guild guild = event.getGuild();
        List<TextChannel> chan = guild.getTextChannelsByName(scheduleChan, false);

        if (!chan.isEmpty())
        {
            // create a message history object
            MessageHistory history = chan.get(0).getHistory();

            // create a consumer
            Consumer<List<Message>> cons = (l) -> {
                for (Message message : l)
                {
                    if (message.getAuthor().getId().equals(Main.getBotSelfUser().getId()))
                    {
                        if (message.getRawContent().startsWith("```java"))
                            channelSettingsManager.loadSettings( message );
                        else
                            scheduleManager.addEntry(message);
                    }
                    else
                        MessageUtilities.deleteMsg( message, null );
                }

                channelSettingsManager.checkChannel(chan.get(0));
            };

            // retrieve history and have the consumer act on it
            history.retrievePast((maxEntries>=0) ? maxEntries*2:50).queue(cons);
        }
    }

    @Override
    public void onGuildLeave( GuildLeaveEvent event )
    {
        for( Integer id : scheduleManager.getEntriesByGuild( event.getGuild().getId() ) )
        {
            scheduleManager.removeId( id );
        }
    }
}