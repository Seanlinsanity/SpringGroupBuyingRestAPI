package com.seanlindev.springframework.services;

import com.seanlindev.springframework.api.dto.AddressDTO;
import com.seanlindev.springframework.api.dto.OrderDto;
import com.seanlindev.springframework.api.dto.ParticipantOrderDto;
import com.seanlindev.springframework.api.dto.ShipmentDto;
import com.seanlindev.springframework.exceptions.OrderServiceException;
import com.seanlindev.springframework.model.OrderStatus;
import com.seanlindev.springframework.model.entities.OrderEntity;
import com.seanlindev.springframework.model.entities.ParticipantOrderEntity;
import com.seanlindev.springframework.model.entities.ProductEntity;
import com.seanlindev.springframework.model.entities.UserEntity;
import com.seanlindev.springframework.repositories.OrderRepository;
import com.seanlindev.springframework.repositories.ParticipantOrderRepository;
import com.seanlindev.springframework.repositories.ProductRepository;
import com.seanlindev.springframework.repositories.UserRepository;
import com.seanlindev.springframework.shared.utils.PublicIdGenerator;
import org.modelmapper.ModelMapper;
import org.modelmapper.TypeToken;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Type;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {

    OrderRepository orderRepository;
    ParticipantOrderRepository participantOrderRepository;
    UserRepository userRepository;
    ProductRepository productRepository;
    PublicIdGenerator publicIdGenerator;
    ShipmentService shipmentService;

    public OrderServiceImpl(OrderRepository orderRepository,
                            ParticipantOrderRepository participantOrderRepository,
                            UserRepository userRepository,
                            ProductRepository productRepository,
                            PublicIdGenerator publicIdGenerator,
                            ShipmentService shipmentService) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.publicIdGenerator = publicIdGenerator;
        this.participantOrderRepository = participantOrderRepository;
        this.shipmentService = shipmentService;
    }

    @Override
    public OrderDto createOrder(OrderDto order) {
        order.setOrderId(publicIdGenerator.generateOrderId(30));
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderId(order.getOrderId());
        orderEntity.setQuantity(order.getQuantity());
        orderEntity.setTitle(order.getTitle());
        orderEntity.setStatus(OrderStatus.CREATED);

        UserEntity ownerEntity = userRepository.findByUserId(order.getOwner().getUserId());
        if (ownerEntity == null) {
            throw new UsernameNotFoundException("Order owner not found: " + order.getOwner().getUserId());
        }
        orderEntity.setOwner(ownerEntity);

        List<ParticipantOrderEntity> participantOrders = order.getParticipantOrders().stream().map(participantOrderDto -> {
            ParticipantOrderEntity participantOrderEntity = new ParticipantOrderEntity();
            participantOrderEntity.setParentOrder(orderEntity);
            participantOrderEntity.setUserId(participantOrderDto.getUserId());
            participantOrderEntity.setQuantity(participantOrderDto.getQuantity());
            participantOrderEntity.setIdentity(publicIdGenerator.generateOrderId(30));
            return participantOrderEntity;
        }).collect(Collectors.toList());
        orderEntity.setParticipantOrders(participantOrders);

        List<ProductEntity> items = order.getItems().stream().map(productDto -> {
            ProductEntity productEntity = productRepository.findByProductId(productDto.getProductId());
            if (productEntity == null) {
                throw new OrderServiceException("Order items are invalid, product id: " + productDto.getProductId());
            }
            return productEntity;
        }).collect(Collectors.toList());
        orderEntity.setItems(items);

        OrderEntity savedOrder = orderRepository.save(orderEntity);
        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
        return modelMapper.map(savedOrder, OrderDto.class);
    }

    public List<OrderDto> getOrdersByOwnerId(String ownerId) {
        UserEntity userEntity = userRepository.findByUserId(ownerId);
        if (userEntity == null) {
            throw new UsernameNotFoundException("Participant not found, id: " + ownerId);
        }
        List<OrderEntity> orderEntityList = orderRepository.findAllByOwnerId(userEntity.getId());
        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
        Type listType = new TypeToken<List<OrderDto>>() {}.getType();
        return modelMapper.map(orderEntityList, listType);
    }

    public OrderDto findByOrderId(String orderId) {
        OrderEntity orderEntity = orderRepository.findByOrderId(orderId);
        if (orderEntity == null) {
            throw new OrderServiceException("Order not found, order: " + orderId);
        }
        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
        return modelMapper.map(orderEntity, OrderDto.class);
    }

    @Transactional
    @Override
    public OrderDto updateOrderParticipants(ParticipantOrderDto participantOrderDto) {
        OrderEntity orderEntity = orderRepository.findByOrderIdForShare(participantOrderDto.getOrderId());
        if (orderEntity == null) {
            throw new OrderServiceException("Order not found, order: " + participantOrderDto.getOrderId());
        }

        if (orderEntity.getStatus() == OrderStatus.PAID) {
            throw new OrderServiceException("You can not join an order which is already paid, order: " + participantOrderDto.getOrderId());
        }

        if (orderEntity.getStatus() == OrderStatus.CANCELLED) {
            throw new OrderServiceException("You can not join an order which is already cancelled, order: " + participantOrderDto.getOrderId());
        }

        UserEntity userEntity = userRepository.findByUserId(participantOrderDto.getUserId());
        if (userEntity == null) {
            throw new UsernameNotFoundException("Participant not found, id: " + participantOrderDto.getUserId());
        }

        ParticipantOrderEntity participantOrderEntity = new ParticipantOrderEntity();
        participantOrderEntity.setIdentity(publicIdGenerator.generateOrderId(30));
        participantOrderEntity.setQuantity(participantOrderDto.getQuantity());
        participantOrderEntity.setUserId(participantOrderDto.getUserId());
        participantOrderEntity.setParentOrder(orderEntity);
        participantOrderRepository.save(participantOrderEntity);

        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
        OrderDto orderDto = modelMapper.map(orderEntity, OrderDto.class);
        return orderDto;
    }

    @Override
    public OrderDto changeOrderStatus(OrderDto orderDto) {
        OrderDto updatedOrder = updateOrderStatus(orderDto);
        if (updatedOrder.getStatus() == OrderStatus.PAID) {
            //REQUEST A SHIPMENT
            ShipmentDto shipmentDto = new ShipmentDto();
            shipmentDto.setOrderId(updatedOrder.getOrderId());
            shipmentDto.setRecipient(updatedOrder.getOwner().getFirstName() + " " + updatedOrder.getOwner().getLastName());
            AddressDTO addressDTO = updatedOrder.getOwner().getAddresses().stream().filter(address -> {
                return address.getType().equals("shipping");
            }).findFirst().get();
            String address = addressDTO.getCountry() + " " + addressDTO.getPostalCode() + " " + addressDTO.getCity() + " " + addressDTO.getStreetName();
            shipmentDto.setAddress(address);

            try {
                String shipmentId = shipmentService.createShipment(shipmentDto);
                updatedOrder.setShipmentId(shipmentId);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage());
            }
        }
        return updatedOrder;
    }

    @Transactional
    public OrderDto updateOrderStatus(OrderDto orderDto) {
        OrderEntity orderEntity = orderRepository.findByOrderIdForUpdate(orderDto.getOrderId());
        if (orderEntity == null) {
            throw new OrderServiceException("Order not found: " + orderDto.getOrderId());
        }
        if (orderDto.getStatus() == OrderStatus.PAID && orderEntity.getStatus() == OrderStatus.PAID) {
            throw new OrderServiceException("Order is already paid, order: " + orderDto.getOrderId());
        }

        if (orderDto.getStatus() == OrderStatus.CANCELLED && orderEntity.getStatus() == OrderStatus.CANCELLED
            || orderDto.getStatus() == OrderStatus.CREATED) {
            throw new OrderServiceException("Invalid order paid status change, order: " + orderDto.getOrderId());
        }
        orderEntity.setStatus(orderDto.getStatus());
        orderRepository.save(orderEntity);

        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
        OrderDto updatedOrderDto = modelMapper.map(orderEntity, OrderDto.class);
        return updatedOrderDto;
    }

    @Override
    public Boolean deleteOrderByOrderId(String orderId) {
        OrderEntity orderEntity = orderRepository.findByOrderId(orderId);
        if (orderEntity == null) {
            return false;
        }
        orderRepository.delete(orderEntity);
        return true;
    }
}
