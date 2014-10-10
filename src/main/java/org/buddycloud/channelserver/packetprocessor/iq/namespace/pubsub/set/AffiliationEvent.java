package org.buddycloud.channelserver.packetprocessor.iq.namespace.pubsub.set;

import java.util.Collection;
import java.util.concurrent.BlockingQueue;

import org.apache.log4j.Logger;
import org.buddycloud.channelserver.Configuration;
import org.buddycloud.channelserver.channel.ChannelManager;
import org.buddycloud.channelserver.db.exception.NodeStoreException;
import org.buddycloud.channelserver.packetprocessor.iq.namespace.pubsub.JabberPubsub;
import org.buddycloud.channelserver.packetprocessor.iq.namespace.pubsub.PubSubElementProcessorAbstract;
import org.buddycloud.channelserver.pubsub.affiliation.Affiliations;
import org.buddycloud.channelserver.pubsub.model.NodeMembership;
import org.buddycloud.channelserver.pubsub.model.NodeSubscription;
import org.buddycloud.channelserver.utils.node.item.payload.Buddycloud;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.dom.DOMElement;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;
import org.xmpp.resultsetmanagement.ResultSet;

public class AffiliationEvent extends PubSubElementProcessorAbstract {

    Element requestedAffiliationElement;
    NodeMembership usersCurrentMembership;
    private Affiliations requestedAffiliation;

    private static final Logger LOGGER = Logger
            .getLogger(AffiliationEvent.class);

    public static final String CAN_NOT_MODIFY_OWN_AFFILIATION = "can-not-modify-own-affiliation";

    /**
     * Constructor
     * 
     * @param outQueue
     *            Outgoing message queue
     * @param channelManager
     *            Data Access Object (DAO)
     */
    public AffiliationEvent(BlockingQueue<Packet> outQueue,
            ChannelManager channelManager) {
        setChannelManager(channelManager);
        setOutQueue(outQueue);
    }

    /**
     * Process incoming stanza
     */
    public void process(Element elm, JID actorJID, IQ reqIQ, Element rsm)
            throws Exception {
        element = elm;
        response = IQ.createResultIQ(reqIQ);
        request = reqIQ;
        actor = actorJID;
        node = element.attributeValue("node");

        if (actor == null) {
            actor = request.getFrom();
        }
        
        if (false == nodeProvided()) {
            outQueue.put(response);
            return;
        }
        
        if (false == Configuration.getInstance().isLocalNode(node)) {
            makeRemoteRequest();
            return;
        }

        try {
            if ((false == validRequestStanza())
                    || (false == checkNodeExists())
                    || (false == actorHasPermissionToAuthorize())
                    || (false == subscriberHasCurrentAffiliation())
                    || (true == userIsModifyingTheirAffiliation())
                    || (false == attemptToChangeAffiliationOfNodeOwner())) {
                outQueue.put(response);
                return;
            }
            saveUpdatedAffiliation();
            sendNotifications();
        } catch (NodeStoreException e) {
            LOGGER.error(e);
            setErrorCondition(PacketError.Type.wait,
                    PacketError.Condition.internal_server_error);
            outQueue.put(response);
            return;
        }
    }

    private boolean userIsModifyingTheirAffiliation() {
        if (actor.toBareJID()
                .equals(requestedAffiliationElement.attributeValue("jid"))) {
            createExtendedErrorReply(PacketError.Type.cancel,
                    PacketError.Condition.not_allowed,
                    CAN_NOT_MODIFY_OWN_AFFILIATION, Buddycloud.NS_ERROR);
            return true;
        }
        return false;
    }

    private boolean attemptToChangeAffiliationOfNodeOwner() {
        if (!usersCurrentMembership.getAffiliation().equals(Affiliations.owner)) {
            return true;
        }
        setErrorCondition(PacketError.Type.modify,
                PacketError.Condition.not_acceptable);
        return false;
    }

    private void sendNotifications() throws Exception {

        outQueue.put(response);

        ResultSet<NodeSubscription> subscribers = channelManager
                .getNodeSubscriptionListeners(node);

        Document document = getDocumentHelper();
        Element message = document.addElement("message");
        Element pubsub = message.addElement("event");
        message.addAttribute("remote-server-discover", "false");
        Element affiliations = pubsub.addElement("affiliations");
        Element affiliation = affiliations.addElement("affiliation");

        pubsub.addNamespace("", JabberPubsub.NS_PUBSUB_EVENT);
        message.addAttribute("from", request.getTo().toString());
        message.addAttribute("type", "headline");

        affiliations.addAttribute("node", node);
        affiliation.addAttribute("jid",
                requestedAffiliationElement.attributeValue("jid"));
        affiliation.addAttribute("affiliation",
                requestedAffiliationElement.attributeValue("affiliation"));
        Message rootElement = new Message(message);

        for (NodeSubscription subscriber : subscribers) {
            Message notification = rootElement.createCopy();
            notification.setTo(subscriber.getListener());
            outQueue.put(notification);
        }

        Collection<JID> admins = getAdminUsers();
        for (JID admin : admins) {
            Message notification = rootElement.createCopy();
            notification.setTo(admin);
            outQueue.put(notification);
        }
    }

    private void saveUpdatedAffiliation() throws NodeStoreException {
        JID jid = new JID(requestedAffiliationElement.attributeValue("jid"));
        Affiliations affiliation = Affiliations.valueOf(requestedAffiliationElement
                .attributeValue("affiliation"));
        channelManager.setUserAffiliation(node, jid, affiliation);
    }

    private boolean nodeProvided() {
        if (null != node) {
            return true;
        }
        response.setType(IQ.Type.error);
        Element nodeIdRequired = new DOMElement("nodeid-required",
                new Namespace("", JabberPubsub.NS_PUBSUB_ERROR));
        Element badRequest = new DOMElement(
                PacketError.Condition.bad_request.toString(), new Namespace("",
                        JabberPubsub.NS_XMPP_STANZAS));
        Element error = new DOMElement("error");
        error.addAttribute("type", "modify");
        error.add(badRequest);
        error.add(nodeIdRequired);
        response.setChildElement(error);
        return false;
    }

    private boolean validRequestStanza() {
        try {
            requestedAffiliationElement = request.getElement().element("pubsub")
                    .element("affiliations").element("affiliation");
            if ((null == requestedAffiliationElement)
                    || (null == requestedAffiliationElement.attribute("jid"))
                    || (null == requestedAffiliationElement.attribute("affiliation"))) {
                setErrorCondition(PacketError.Type.modify,
                        PacketError.Condition.bad_request);
                return false;
            }
            requestedAffiliation = Affiliations.createFromString(
                    requestedAffiliationElement.attributeValue("affiliation"));
        } catch (NullPointerException e) {
            LOGGER.error(e);
            setErrorCondition(PacketError.Type.modify,
                    PacketError.Condition.bad_request);
            return false;
        }
        requestedAffiliationElement.addAttribute(
                "affiliation",
                requestedAffiliation.toString());
        return true;
    }

    private boolean subscriberHasCurrentAffiliation() throws NodeStoreException {
        usersCurrentMembership = channelManager.getNodeMembership(node,
                new JID(requestedAffiliationElement.attributeValue("jid")));

        if (usersCurrentMembership.getAffiliation().equals(Affiliations.none)) {
            setErrorCondition(PacketError.Type.modify,
                    PacketError.Condition.unexpected_request);
            return false;
        }
        return true;
    }

    private boolean actorHasPermissionToAuthorize() throws NodeStoreException {

        NodeMembership membership = channelManager.getNodeMembership(node,
                actor);
        if (!membership.getAffiliation().canAuthorize()) {
            setErrorCondition(PacketError.Type.auth,
                    PacketError.Condition.not_authorized);
            return false;
        }
        if (membership.getAffiliation().equals(Affiliations.owner)) {
            return true;
        }
        if (requestedAffiliation.equals(Affiliations.moderator) || requestedAffiliation.equals(Affiliations.owner)) {
            setErrorCondition(PacketError.Type.auth,
                    PacketError.Condition.forbidden);
            return false;
        }
        return true;
    }

    private boolean checkNodeExists() throws NodeStoreException {
        if (false == channelManager.nodeExists(node)) {
            setErrorCondition(PacketError.Type.cancel,
                    PacketError.Condition.item_not_found);
            return false;
        }
        return true;
    }

    private void makeRemoteRequest() throws InterruptedException {
        request.setTo(new JID(node.split("/")[2]).getDomain());
        Element actor = request.getElement().element("pubsub")
                .addElement("actor", Buddycloud.NS);
        actor.addText(request.getFrom().toBareJID());
        outQueue.put(request);
    }

    /**
     * Determine if this class is capable of processing incoming stanza
     */
    public boolean accept(Element elm) {
        return elm.getName().equals("affiliations");
    }
}
