/*
 * Copyright © Wynntils 2023.
 * This file is released under AGPLv3. See LICENSE for full license details.
 */
package com.wynntils.models.players;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Managers;
import com.wynntils.core.components.Model;
import com.wynntils.core.components.Models;
import com.wynntils.handlers.chat.MessageType;
import com.wynntils.handlers.chat.event.ChatMessageReceivedEvent;
import com.wynntils.mc.event.SetPlayerTeamEvent;
import com.wynntils.models.players.event.HadesRelationsUpdateEvent;
import com.wynntils.models.players.event.PartyEvent;
import com.wynntils.models.players.hades.event.HadesEvent;
import com.wynntils.models.worlds.WorldStateModel;
import com.wynntils.models.worlds.event.WorldStateEvent;
import com.wynntils.models.worlds.type.WorldState;
import com.wynntils.utils.mc.ComponentUtils;
import com.wynntils.utils.mc.McUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * This model handles the player's party relations.
 */
public final class PartyModel extends Model {
    // region Party Regexes
    /*
    Regexes should be named with this format:
    PARTY_ACTION_INVOLVED_(DETAIL)
    where:
    PARTY should be the first word in the name
    ACTION should be something like JOIN, LEAVE, LIST, etc.
    INVOLVED should be the player that is affected by the action:
    - ALL should be used if the action affects all players in the party
    - LEADER should be used if the action affects the party leader
    - SELF should be used if the action affects the player
    - OTHER should be used if the action affects another player(s), but not ALL players
    DETAIL (optional) should be a descriptor if necessary
    */
    private static final Pattern PARTY_LIST_ALL = Pattern.compile("§eParty members: (.*)");
    private static final Pattern PARTY_LIST_LEADER = Pattern.compile("§r§b(.+)");
    private static final Pattern PARTY_LIST_SELF_FAILED = Pattern.compile("§eYou must be in a party to list\\.");

    private static final Pattern PARTY_LEAVE_OTHER = Pattern.compile("§e(.+) has left the party\\.");
    private static final Pattern PARTY_LEAVE_SELF_ALREADYLEFT = Pattern.compile("§eYou must be in a party to leave\\.");

    // This is a special case; Wynn sends the same message for when we leave a party or get kicked from a party
    private static final Pattern PARTY_LEAVE_SELF_KICK = Pattern.compile("§eYou have been removed from the party\\.");

    private static final Pattern PARTY_JOIN_OTHER = Pattern.compile("§e(.+) has joined the party\\.");
    private static final Pattern PARTY_JOIN_OTHER_SWITCH =
            Pattern.compile("§eSay hello to (.+) which just joined your party!");
    private static final Pattern PARTY_JOIN_SELF = Pattern.compile("§eYou have successfully joined the party\\.");

    private static final Pattern PARTY_PROMOTE_OTHER = Pattern.compile("§eSuccessfully promoted (.+) to party leader!");
    private static final Pattern PARTY_PROMOTE_SELF =
            Pattern.compile("§eYou are now the leader of this party! Type /party for a list of commands\\.");

    private static final Pattern PARTY_DISBAND_ALL = Pattern.compile("§eYour party has been disbanded\\.");
    private static final Pattern PARTY_DISBAND_SELF =
            Pattern.compile("§eYour party has been disbanded since you were the only member remaining\\.");

    private static final Pattern PARTY_CREATE_SELF = Pattern.compile("§eYou have successfully created a party\\.");

    private static final Pattern PARTY_KICK_OTHER = Pattern.compile("§eYou have kicked the player from the party.\\.");
    // endregion

    private boolean expectingPartyMessage = false; // Whether the client is expecting a response from "/party list"
    private boolean nextKickHandled = false; // Whether the next "/party kick" sent by the client is being handled

    private boolean inParty; // Whether the player is in a party
    private String partyLeader = null; // The name of the party leader
    private HashSet<String> partyMembers = new HashSet<>(); // A set of Strings representing all party members
    private HashSet<String> offlineMembers =
            new HashSet<>(); // A set of Strings representing all offline (disconnected) party members

    public PartyModel(WorldStateModel worldStateModel) {
        super(List.of(worldStateModel));
        resetData();
    }

    @SubscribeEvent
    public void onAuth(HadesEvent.Authenticated event) {
        if (!Models.WorldState.onWorld()) return;

        requestData();
    }

    @SubscribeEvent
    public void onWorldStateChange(WorldStateEvent event) {
        if (event.getNewState() == WorldState.WORLD) {
            requestData();
        } else {
            resetData();
        }
    }

    @SubscribeEvent
    public void onChatReceived(ChatMessageReceivedEvent event) {
        if (event.getMessageType() != MessageType.FOREGROUND) return;

        String coded = event.getOriginalCodedMessage();

        if (tryParsePartyMessages(coded)) {
            return;
        }

        if (expectingPartyMessage) {
            if (tryParseNoPartyMessage(coded) || tryParsePartyList(coded)) {
                event.setCanceled(true);
                expectingPartyMessage = false;
                return;
            }
        }
    }

    private boolean tryParsePartyMessages(String coded) {
        if (PARTY_CREATE_SELF.matcher(coded).matches()) {
            WynntilsMod.info("Player created a new party.");

            inParty = true;
            partyLeader = McUtils.player().getName().getString();
            partyMembers = new HashSet<>(Set.of(partyLeader));
            WynntilsMod.postEvent(
                    new HadesRelationsUpdateEvent.PartyList(partyMembers, HadesRelationsUpdateEvent.ChangeType.RELOAD));
            WynntilsMod.postEvent(new PartyEvent.Listed());
            return true;
        }

        if (PARTY_DISBAND_ALL.matcher(coded).matches()
                || PARTY_LEAVE_SELF_KICK.matcher(coded).matches()
                || PARTY_LEAVE_SELF_ALREADYLEFT.matcher(coded).matches()
                || PARTY_DISBAND_SELF.matcher(coded).matches()) {
            WynntilsMod.info("Player left the party.");

            resetData(); // (!) resetData() already posts events for both HadesRelationsUpdateEvent and PartyEvent
            return true;
        }

        if (PARTY_JOIN_SELF.matcher(coded).matches()) {
            WynntilsMod.info("Player joined a party.");
            requestData();
            return true;
        }

        Matcher matcher = PARTY_JOIN_OTHER.matcher(coded);
        if (matcher.matches()) {
            String player = matcher.group(1);

            WynntilsMod.info("Player's party has a new member: " + player);

            partyMembers.add(player);
            WynntilsMod.postEvent(
                    new HadesRelationsUpdateEvent.PartyList(Set.of(player), HadesRelationsUpdateEvent.ChangeType.ADD));
            WynntilsMod.postEvent(new PartyEvent.OtherJoined(player));
            return true;
        }

        matcher = PARTY_JOIN_OTHER_SWITCH.matcher(coded);
        if (matcher.matches()) {
            String player = matcher.group(1);

            WynntilsMod.info("Player's party has a new member #2: " + player);

            partyMembers.add(player);
            WynntilsMod.postEvent(
                    new HadesRelationsUpdateEvent.PartyList(Set.of(player), HadesRelationsUpdateEvent.ChangeType.ADD));
            WynntilsMod.postEvent(new PartyEvent.OtherJoined(player));
            return true;
        }

        matcher = PARTY_LEAVE_OTHER.matcher(coded);
        if (matcher.matches()) {
            String player = matcher.group(1);

            WynntilsMod.info("Other player left player's party: " + player);

            partyMembers.remove(player);
            WynntilsMod.postEvent(new HadesRelationsUpdateEvent.PartyList(
                    Set.of(player), HadesRelationsUpdateEvent.ChangeType.REMOVE));
            WynntilsMod.postEvent(new PartyEvent.OtherLeft(player));
            return true;
        }

        matcher = PARTY_PROMOTE_OTHER.matcher(coded);
        if (matcher.matches()) {
            String player = matcher.group(1);

            WynntilsMod.info("Player's party has a new leader: " + player);

            partyLeader = player;
            return true;
        }

        matcher = PARTY_PROMOTE_SELF.matcher(coded);
        if (matcher.matches()) {
            WynntilsMod.info("Player has been promoted to party leader.");

            partyLeader = McUtils.player().getName().getString();
            return true;
        }

        matcher = PARTY_KICK_OTHER.matcher(coded);
        if (matcher.matches()) {
            WynntilsMod.info("Other player was kicked from player's party");

            /*
            (!) This message/matcher is ONLY triggered when the player sends "/party kick". Since that message
            does not tell us who we actually kicked, the #processPartyKick() method handles all the logic for this event.
            That method should be invoked whereever we run "/party kick".
            HOWEVER: if the player decides to run "/party kick" manually from the chat for whatever reason,
            this event will be triggered and the #processPartyKick() method will not be invoked.

            So, the nextKickHandled boolean will tell us if the kick was already handled.
            If it was not handled, we have no choice but to re-request the party data.

            But also, we have a delay here because it takes time for the server to update the leaderboard. (Which is partially
            the reason why we don't like to use the request function too much.)
            */

            if (!nextKickHandled) {
                Managers.TickScheduler.scheduleLater(this::requestData, 2);
            } else {
                nextKickHandled = false;
            }

            return true;
        }

        return false;
    }

    private boolean tryParseNoPartyMessage(String coded) {
        if (PARTY_LIST_SELF_FAILED.matcher(coded).matches()) {
            resetData();
            WynntilsMod.info("Player is not in a party.");
            return true;
        }

        return false;
    }

    private boolean tryParsePartyList(String coded) {
        Matcher matcher = PARTY_LIST_ALL.matcher(coded);
        if (!matcher.matches()) return false;

        String[] partyList = matcher.group(1).split(", ");
        partyMembers.clear();

        for (String member : partyList) {
            Matcher m = PARTY_LIST_LEADER.matcher(member);
            if (m.matches()) {
                partyLeader = m.group(1);
            }

            partyMembers.add(ComponentUtils.stripFormatting(member));
        }

        inParty = true;
        WynntilsMod.postEvent(
                new HadesRelationsUpdateEvent.PartyList(partyMembers, HadesRelationsUpdateEvent.ChangeType.RELOAD));
        WynntilsMod.postEvent(new PartyEvent.Listed());
        WynntilsMod.info("Successfully updated party list, user has " + partyList.length + " party members.");

        offlineMembers.clear();
        offlineMembers.addAll(partyMembers);
        offlineMembers.removeAll(McUtils.mc().level.getScoreboard().getTeamNames());
        WynntilsMod.info("Successfully updated offline members, user's party has " + offlineMembers.size()
                + " offline members.");

        return true;
    }

    /**
     * Wynncraft doesn't tell us who we kicked when we do "/party kick", and manually requesting the party list
     * is slow and expensive. So this method exists.
     */
    private void processPartyKick(String playerName) {
        partyMembers.remove(playerName);
        offlineMembers.remove(playerName);

        WynntilsMod.postEvent(new HadesRelationsUpdateEvent.PartyList(
                Set.of(playerName), HadesRelationsUpdateEvent.ChangeType.REMOVE));
        WynntilsMod.postEvent(new PartyEvent.OtherLeft(playerName));
    }

    /**
     * Resets all party data to a state where the player is not in a party.
     * Posts events for both PartyEvent and HadesRelationsUpdateEvent.
     */
    private void resetData() {
        partyMembers = new HashSet<>();
        partyLeader = null;
        inParty = false;
        offlineMembers = new HashSet<>();

        WynntilsMod.postEvent(
                new HadesRelationsUpdateEvent.PartyList(partyMembers, HadesRelationsUpdateEvent.ChangeType.RELOAD));
        WynntilsMod.postEvent(new PartyEvent.Listed());
    }

    /**
     * Sends "/party list" to Wynncraft and waits for the response.
     * When the response is received, partyMembers and partyLeader will be updated.
     * After that, the offlineMembers list will be updated from scoreboard data.
     */
    public void requestData() {
        if (McUtils.player() == null) return;

        expectingPartyMessage = true;
        McUtils.sendCommand("party list");
        WynntilsMod.info("Requested party list from Wynncraft.");
    }

    public boolean isInParty() {
        return inParty;
    }

    public String getPartyLeader() {
        return partyLeader;
    }

    public Set<String> getPartyMembers() {
        return partyMembers;
    }

    public Set<String> getOfflineMembers() {
        return offlineMembers;
    }

    @SubscribeEvent
    public void onSetTeam(SetPlayerTeamEvent e) {
        if (!inParty) return;

        if (e.getMethod() == 0) { // ADD, so player joined the server
            offlineMembers.remove(e.getTeamName());
            WynntilsMod.postEvent(new PartyEvent.OtherReconnected(e.getTeamName()));
        } else if (e.getMethod() == 1) { // REMOVE, so player left the server
            if (partyMembers.contains(e.getTeamName())) {
                offlineMembers.add(e.getTeamName());
                WynntilsMod.postEvent(new PartyEvent.OtherDisconnected(e.getTeamName()));
            }
        }
    }

    // region Party Commands
    /**
     * Kicks a player from the party.
     */
    public void partyKick(String player) {
        nextKickHandled = true;
        McUtils.sendCommand("party kick " + player);
        processPartyKick(player);
    }

    /**
     * Promotes a player to party leader.
     */
    public void partyPromote(String player) {
        McUtils.sendCommand("party promote " + player);
    }

    /**
     * Invites a player to the party. Creates a party if the player is not in one.
     */
    public void partyInvite(String player) {
        if (!inParty) partyCreate();
        McUtils.sendCommand("party invite " + player);
    }

    /**
     * Leaves the party.
     */
    public void partyLeave() {
        McUtils.sendCommand("party leave");
    }

    /**
     * Disbands the party.
     */
    public void partyDisband() {
        McUtils.sendCommand("party disband");
    }

    /**
     * Creates a party.
     */
    public void partyCreate() {
        McUtils.sendCommand("party create");
    }

    /**
     * Kicks everyone in offlineMembers.
     * Consider running {@link #requestData()} before this.
     */
    public void partyKickOffline() {
        offlineMembers.forEach(this::partyKick);
    }
    // endregion
}